package dev.notypie.domain.common

import java.time.LocalDateTime
import java.util.regex.Pattern

infix fun String.shouldNotInclude(invalidPattern: String) {
    if (Pattern.compile(invalidPattern).matcher(this).matches()) {
        throw IllegalArgumentException("Invalid string")
    }
}

infix fun MutableList<String>.shouldNotInclude(invalidPattern: String) {
    this.forEach { string -> string shouldNotInclude invalidPattern }
}

infix fun String.and(other: String) = mutableListOf(this, other)

infix fun MutableList<String>.and(other: String) = this.apply { add(other) }

infix fun LocalDateTime.isLaterOrNow(other: LocalDateTime): Boolean = this.isAfter(other) || this.isEqual(other)

infix fun LocalDateTime.isLater(other: LocalDateTime): Boolean = this.isAfter(other)

class ValidationBuilder {
    private val errors = mutableListOf<String>()

    infix fun String.and(other: String): Pair<String, String> = this to other

    infix fun Pair<String, String>.shouldNotContain(forbidden: List<String>) {
        forbidden.forEach { word ->
            if (first.contains(word, ignoreCase = true) ||
                second.contains(word, ignoreCase = true)
            ) {
                errors.add("금지된 문자가 포함되어 있습니다: $word")
            }
        }
    }

    infix fun Int.shouldBeLessThan(max: Int) {
        if (this >= max) {
            errors.add("${max}보다 작아야 합니다")
        }
    }

    infix fun LocalDateTime.shouldBeAfter(other: LocalDateTime) {
        if (!this.isAfter(other)) {
            errors.add("$this 는 $other 이후여야 합니다")
        }
    }

    fun validate() {
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString(", "))
        }
    }
}

fun validate(block: ValidationBuilder.() -> Unit) {
    ValidationBuilder().apply(block).validate()
}
