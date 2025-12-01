package dev.notypie.domain.common

import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.DomainValidationException
import dev.notypie.domain.common.error.ExceptionArgument
import java.time.LocalDateTime

internal class DomainValidationBuilder {
    private val errors = mutableListOf<ExceptionArgument>()

    data class Field<T>(
        val name: String,
        val value: T,
    )

    infix fun <T> String.of(value: T): Field<T> = Field(name = this, value = value)

    fun notBlank(block: StringFieldsBuilder.() -> Unit) {
        val builder = StringFieldsBuilder()
        builder.block()
        builder.fields.forEach { field ->
            if (field.value.isBlank()) {
                errors.add(
                    ExceptionArgument(
                        fieldName = field.name,
                        value = field.value,
                        reason = "$field must not be blank",
                    ),
                )
            }
        }
    }

    class StringFieldsBuilder {
        val fields = mutableListOf<Field<String>>()

        infix fun String.of(value: String) {
            fields.add(Field(this, value))
        }
    }

    infix fun Field<String>.shouldBeShorterThan(max: Int) {
        if (value.length > max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "length must be less than $max (current: ${value.length})",
                ),
            )
        }
    }

    infix fun Field<String>.shouldBeLongerThan(min: Int) {
        if (value.length < min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "length must be greater than $min (current: ${value.length})",
                ),
            )
        }
    }

    infix fun Field<String>.shouldMatchPattern(pattern: Regex) {
        if (!pattern.matches(input = value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "does not match required pattern: ${pattern.pattern}",
                ),
            )
        }
    }

    infix fun Field<String>.shouldMatchPattern(pattern: String) {
        if (!pattern.toRegex().matches(input = value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "does not match required pattern: $pattern",
                ),
            )
        }
    }

    fun Field<String>.shouldBeEmail() {
        val emailPattern = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!emailPattern.matches(value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "must be a valid email address",
                ),
            )
        }
    }

    infix fun Field<String>.shouldBeOneOf(options: Collection<String>) {
        if (value !in options) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "must be one of: ${options.joinToString(", ")}",
                ),
            )
        }
    }

    infix fun Field<Int>.shouldBeLessThan(max: Int) {
        if (value >= max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be less than $max",
                ),
            )
        }
    }

    infix fun Field<Int>.shouldBeLessThanOrEqualTo(max: Int) {
        if (value > max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be less than or equal to $max",
                ),
            )
        }
    }

    infix fun Field<Int>.shouldBeGreaterThan(min: Int) {
        if (value <= min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be greater than $min",
                ),
            )
        }
    }

    infix fun Field<Int>.shouldBeGreaterThanOrEqualTo(min: Int) {
        if (value < min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be greater than or equal to $min",
                ),
            )
        }
    }

    infix fun Field<Int>.shouldBeBetween(range: IntRange) {
        if (value !in range) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be between ${range.first} and ${range.last}",
                ),
            )
        }
    }

    fun Field<Int>.shouldBePositive() {
        if (value <= 0) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be positive",
                ),
            )
        }
    }

    fun Field<Int>.shouldBeNonNegative() {
        if (value < 0) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be non-negative",
                ),
            )
        }
    }

    infix fun Field<LocalDateTime>.shouldBeAfter(other: LocalDateTime) {
        if (!value.isAfter(other)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be after $other",
                ),
            )
        }
    }

    infix fun Field<LocalDateTime>.shouldBeBefore(other: LocalDateTime) {
        if (!value.isBefore(other)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be before $other",
                ),
            )
        }
    }

    fun Field<LocalDateTime>.shouldBeInFuture() {
        val now = LocalDateTime.now()
        if (!value.isAfter(now)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be in the future",
                ),
            )
        }
    }

    fun Field<LocalDateTime>.shouldBeInPast() {
        val now = LocalDateTime.now()
        if (!value.isBefore(now)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be in the past",
                ),
            )
        }
    }

    // ============ Collection Validations ============
    infix fun <T> Field<Collection<T>>.shouldNotBeEmpty(message: String = "must not be empty") {
        if (value.isEmpty()) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = "[]",
                    reason = message,
                ),
            )
        }
    }

    infix fun <T> Field<Collection<T>>.shouldHaveSize(size: Int) {
        if (value.size != size) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must have exactly $size elements (current: ${value.size})",
                ),
            )
        }
    }

    infix fun <T> Field<Collection<T>>.shouldHaveMinSize(min: Int) {
        if (value.size < min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must have at least $min elements (current: ${value.size})",
                ),
            )
        }
    }

    infix fun <T> Field<Collection<T>>.shouldHaveMaxSize(max: Int) {
        if (value.size > max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must have at most $max elements (current: ${value.size})",
                ),
            )
        }
    }

    // ============ Custom Validations ============
    infix fun <T> Field<T>.shouldSatisfy(predicate: (T) -> Boolean) {
        if (!predicate(value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "does not satisfy the required condition",
                ),
            )
        }
    }

    fun <T> Field<T>.shouldSatisfy(message: String, predicate: (T) -> Boolean) {
        if (!predicate(value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = message,
                ),
            )
        }
    }

    fun addError(fieldName: String, value: String, reason: String) {
        errors.add(ExceptionArgument(fieldName, value, reason))
    }

    fun hasErrors(): Boolean = errors.isNotEmpty()

    fun getErrors(): List<ExceptionArgument> = errors.toList()

    fun validate(domain: String) {
        if (errors.isNotEmpty()) {
            throw DomainValidationException(
                domain = domain,
                details = errors,
                errorCode = CommandErrorCode.VALIDATION_FAILED,
            )
        }
    }
}

internal fun validate(domain: String, block: DomainValidationBuilder.() -> Unit) {
    DomainValidationBuilder().apply(block).validate(domain)
}

internal fun validateAndReturn(domain: String, block: DomainValidationBuilder.() -> Unit): List<ExceptionArgument> =
    DomainValidationBuilder().apply(block).getErrors()
