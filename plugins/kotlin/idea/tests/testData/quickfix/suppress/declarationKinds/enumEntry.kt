// "Suppress 'DIVISION_BY_ZERO' for enum entry A" "true"

enum class E {
    A {
        fun foo() = 2 / <caret>0
    }
}
