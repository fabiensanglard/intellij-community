// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.workspaceModel.storage.tests

import com.intellij.platform.workspaceModel.storage.testEntities.entities.MySource
import com.intellij.platform.workspaceModel.storage.testEntities.entities.OptionalOneToOneChildEntity
import com.intellij.platform.workspaceModel.storage.testEntities.entities.OptionalOneToOneParentEntity
import com.intellij.workspaceModel.storage.impl.assertConsistency
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OptionalOneToOne {
  @Test
  fun `one and one`() {
    val builder = createEmptyBuilder()
    val parent = OptionalOneToOneParentEntity(MySource)
    builder.addEntity(parent)

    builder.addEntity(OptionalOneToOneChildEntity("One", MySource) {
      this.parent = parent
    })
    builder.addEntity(OptionalOneToOneChildEntity("Two", MySource) {
      this.parent = parent
    })

    assertNull(builder.entities(OptionalOneToOneChildEntity::class.java).single { it.data == "One" }.parent)
    assertNotNull(builder.entities(OptionalOneToOneChildEntity::class.java).single { it.data == "Two" }.parent)
    builder.assertConsistency()
  }
}