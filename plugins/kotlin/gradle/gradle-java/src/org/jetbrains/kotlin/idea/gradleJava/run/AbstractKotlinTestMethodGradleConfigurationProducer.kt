// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.gradleJava.run

import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.ConfigurationFromContextImpl
import com.intellij.execution.junit.InheritorChooser
import com.intellij.execution.junit2.PsiMemberParameterizedLocation
import com.intellij.execution.junit2.info.MethodLocation
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.idea.base.facet.isNewMultiPlatformModule
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.gradle.run.KotlinGradleConfigurationProducer
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.plugins.gradle.execution.test.runner.TestMethodGradleConfigurationProducer
import org.jetbrains.plugins.gradle.execution.test.runner.applyTestConfiguration
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.createTestFilterFrom

abstract class AbstractKotlinMultiplatformTestMethodGradleConfigurationProducer : AbstractKotlinTestMethodGradleConfigurationProducer() {
    override val forceGradleRunner: Boolean get() = true
    override val hasTestFramework: Boolean get() = true

    private val mppTestTasksChooser = MultiplatformTestTasksChooser()

    abstract fun isApplicable(module: Module, platform: TargetPlatform): Boolean

    final override fun isApplicable(module: Module): Boolean {
        if (!module.isNewMultiPlatformModule) {
            return false
        }

        val platform = module.platform ?: return false
        return isApplicable(module, platform)
    }

    private fun shouldDisgraceConfiguration(other: ConfigurationFromContext): Boolean {
        return other.isJpsJunitConfiguration() ||
                (other as? ConfigurationFromContextImpl)?.configurationProducer is TestMethodGradleConfigurationProducer
    }

    override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return shouldDisgraceConfiguration(other)
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return shouldDisgraceConfiguration(other)
    }

    override fun onFirstRun(fromContext: ConfigurationFromContext, context: ConfigurationContext, performRunnable: Runnable) {
        val psiMethod = fromContext.sourceElement as PsiMethod
        val psiClass = psiMethod.containingClass ?: return

        val inheritorChooser = object : InheritorChooser() {
            override fun runForClasses(classes: List<PsiClass>, method: PsiMethod?, context: ConfigurationContext, runnable: Runnable) {
                chooseTestClassConfiguration(fromContext, context, runnable, psiMethod, *classes.toTypedArray())
            }

            override fun runForClass(aClass: PsiClass, psiMethod: PsiMethod, context: ConfigurationContext, runnable: Runnable) {
                chooseTestClassConfiguration(fromContext, context, runnable, psiMethod, aClass)
            }
        }
        if (inheritorChooser.runMethodInAbstractClass(context, performRunnable, psiMethod, psiClass)) return
        chooseTestClassConfiguration(fromContext, context, performRunnable, psiMethod, psiClass)
    }

    override fun getAllTestsTaskToRun(
        context: ConfigurationContext,
        element: PsiMethod,
        chosenElements: List<PsiClass>
    ): List<TestTasksToRun> {
        val tasks = mppTestTasksChooser.listAvailableTasks(listOf(element))
        val wildcardFilter = createTestFilterFrom(element.containingClass!!, element)
        return tasks.map { TestTasksToRun(it, wildcardFilter) }
    }

    private fun chooseTestClassConfiguration(
        fromContext: ConfigurationFromContext,
        context: ConfigurationContext,
        performRunnable: Runnable,
        psiMethod: PsiMethod,
        vararg classes: PsiClass
    ) {
        val dataContext = MultiplatformTestTasksChooser.createContext(context.dataContext, psiMethod.name)

        val contextualSuffix = when (context.location) {
            is PsiMemberParameterizedLocation -> (context.location as? PsiMemberParameterizedLocation)?.paramSetName?.trim('[', ']')
            is MethodLocation -> "jvm"  // jvm, being default target, is treated differently
            else -> null // from gutters
        }

        mppTestTasksChooser.multiplatformChooseTasks(context.project, dataContext, classes.asList(), contextualSuffix) { tasks ->
            val configuration = fromContext.configuration as GradleRunConfiguration
            val settings = configuration.settings

            val result = settings.applyTestConfiguration(context.module, tasks, *classes) {
                var filters = createTestFilterFrom(context.location, it, psiMethod)
                if (context.location is PsiMemberParameterizedLocation && contextualSuffix != null) {
                    filters = filters.replace("[*$contextualSuffix*]", "")
                }
                filters
            }

            settings.externalProjectPath = ExternalSystemApiUtil.getExternalProjectPath(context.module)

            if (result) {
                configuration.name = (if (classes.size == 1) classes[0].name!! + "." else "") + psiMethod.name
                performRunnable.run()
            } else {
                LOG.warn("Cannot apply method test configuration, uses raw run configuration")
                performRunnable.run()
            }
        }
    }
}

abstract class AbstractKotlinTestMethodGradleConfigurationProducer
    : TestMethodGradleConfigurationProducer(), KotlinGradleConfigurationProducer {
    override fun isConfigurationFromContext(configuration: GradleRunConfiguration, context: ConfigurationContext): Boolean {
        if (!context.check()) {
            return false
        }

        if (!forceGradleRunner) {
            return super.isConfigurationFromContext(configuration, context)
        }

        if (GradleConstants.SYSTEM_ID != configuration.settings.externalSystemId) return false
        return doIsConfigurationFromContext(configuration, context)
    }

    override fun setupConfigurationFromContext(
        configuration: GradleRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        if (!context.check()) {
            return false
        }

        if (!forceGradleRunner) {
            return super.setupConfigurationFromContext(configuration, context, sourceElement)
        }

        if (GradleConstants.SYSTEM_ID != configuration.settings.externalSystemId) return false
        if (sourceElement.isNull) return false

        (configuration as? GradleRunConfiguration)?.apply {
            isDebugServerProcess = false
            isRunAsTest = true
        }
        return doSetupConfigurationFromContext(configuration, context, sourceElement)
    }

    private fun ConfigurationContext.check(): Boolean {
        return hasTestFramework && module != null && isApplicable(module)
    }

    override fun getPsiMethodForLocation(contextLocation: Location<*>) = getTestMethodForKotlinTest(contextLocation)

    override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return checkShouldReplace(self, other) || super.isPreferredConfiguration(self, other)
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        return checkShouldReplace(self, other) || super.shouldReplace(self, other)
    }

    private fun checkShouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean {
        if (self.isProducedBy(javaClass)) {
            if (other.isProducedBy(TestMethodGradleConfigurationProducer::class.java) ||
                other.isProducedBy(AbstractKotlinTestClassGradleConfigurationProducer::class.java)
            ) {
                return true
            }
        }

        return false
    }
}
