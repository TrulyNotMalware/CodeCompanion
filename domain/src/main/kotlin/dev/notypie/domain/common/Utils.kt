package dev.notypie.domain.common

import dev.notypie.domain.command.exceptions.ValidationException
import dev.notypie.domain.command.exceptions.ValidationExceptionWithName
import dev.notypie.domain.common.error.CommonErrorCode
import dev.notypie.domain.common.error.ExceptionArgument
import java.time.LocalDateTime

class ValidationBuilder {
    private val errors = mutableListOf<ExceptionArgument>()

    data class Field<T>(
        val name: String,
        val value: T,
    )

    infix fun <T> String.of(value: T): Field<T> = Field(name = this, value = value)

    // ============ And / Or Chaining ============
    infix fun <T> Field<T>.and(block: ValidationBuilder.(Field<T>) -> Unit): Field<T> {
        block(this)
        return this
    }

    infix fun <T> Field<T>.or(block: ValidationBuilder.(Field<T>) -> Unit): Field<T> {
        val before = errors.size
        block(this)
        if (before < errors.size) {
            repeat(times = errors.size - before) { errors.removeLast() }
        }
        return this
    }

    infix fun <T> Field<T?>.shouldNotBeNullAnd(block: ValidationBuilder.(Field<T>) -> Unit): Field<T?> {
        if (value == null) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = "null",
                    reason = "must not be null",
                ),
            )
        } else {
            @Suppress("UNCHECKED_CAST")
            block(Field(name, value as T))
        }
        return this
    }

    infix fun <T> Field<T?>.ifNotNull(block: ValidationBuilder.(Field<T>) -> Unit): Field<T?> {
        if (value != null) {
            @Suppress("UNCHECKED_CAST")
            block(Field(name, value as T))
        }
        return this
    }

    // ============ String Validations ============
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

    infix fun Field<String>.shouldBeShorterThan(max: Int): Field<String> {
        if (value.length > max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "length must be less than $max (current: ${value.length})",
                ),
            )
        }
        return this
    }

    infix fun Field<String>.shouldBeLongerThan(min: Int): Field<String> {
        if (value.length < min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "length must be greater than $min (current: ${value.length})",
                ),
            )
        }
        return this
    }

    infix fun Field<String>.shouldMatchPattern(pattern: Regex): Field<String> {
        if (!pattern.matches(input = value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "does not match required pattern: ${pattern.pattern}",
                ),
            )
        }
        return this
    }

    infix fun Field<String>.shouldMatchPattern(pattern: String): Field<String> {
        if (!pattern.toRegex().matches(input = value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value,
                    reason = "does not match required pattern: $pattern",
                ),
            )
        }
        return this
    }

    fun Field<String>.shouldBeEmail(): Field<String> {
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
        return this
    }

    fun Field<String?>.shouldNotBeNullOrBlank(): Field<String?> {
        if (value.isNullOrBlank()) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value ?: "null",
                    reason = "must not be null or blank",
                ),
            )
        }
        return this
    }

    // ============ Int Validations ============
    infix fun Field<Int>.shouldBeLessThan(max: Int): Field<Int> {
        if (value >= max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be less than $max",
                ),
            )
        }
        return this
    }

    infix fun Field<Int>.shouldBeLessThanOrEqualTo(max: Int): Field<Int> {
        if (value > max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be less than or equal to $max",
                ),
            )
        }
        return this
    }

    infix fun Field<Int>.shouldBeGreaterThan(min: Int): Field<Int> {
        if (value <= min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be greater than $min",
                ),
            )
        }
        return this
    }

    infix fun Field<Int>.shouldBeGreaterThanOrEqualTo(min: Int): Field<Int> {
        if (value < min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be greater than or equal to $min",
                ),
            )
        }
        return this
    }

    infix fun Field<Int>.shouldBeBetween(range: IntRange): Field<Int> {
        if (value !in range) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be between ${range.first} and ${range.last}",
                ),
            )
        }
        return this
    }

    /**
     * Validates that the integer field is positive (greater than zero).
     */
    fun Field<Int>.shouldBePositive(): Field<Int> {
        if (value <= 0) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be positive",
                ),
            )
        }
        return this
    }

    fun Field<Int>.shouldBeNegative(): Field<Int> {
        if (value >= 0) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be positive",
                ),
            )
        }
        return this
    }

    fun Field<Int>.shouldBeNonNegative(): Field<Int> {
        if (value < 0) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be non-negative",
                ),
            )
        }
        return this
    }

    // ============ LocalDateTime Validations ============
    infix fun Field<LocalDateTime>.shouldBeAfter(other: LocalDateTime): Field<LocalDateTime> {
        if (!value.isAfter(other)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be after $other",
                ),
            )
        }
        return this
    }

    infix fun Field<LocalDateTime>.shouldBeBefore(other: LocalDateTime): Field<LocalDateTime> {
        if (!value.isBefore(other)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be before $other",
                ),
            )
        }
        return this
    }

    fun Field<LocalDateTime>.shouldBeInFuture(): Field<LocalDateTime> {
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
        return this
    }

    fun Field<LocalDateTime>.shouldBeInPast(): Field<LocalDateTime> {
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
        return this
    }

    // ============ Collection Validations ============
    infix fun <T, C : Collection<T>> Field<C>.shouldHaveSize(size: Int): Field<C> {
        if (value.size != size) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must have exactly $size elements (current: ${value.size})",
                ),
            )
        }
        return this
    }

    infix fun <T, C : Collection<T>> Field<C>.shouldHaveMinSize(min: Int): Field<C> {
        if (value.size < min) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must have at least $min elements (current: ${value.size})",
                ),
            )
        }
        return this
    }

    infix fun <T, C : Collection<T>> Field<C>.shouldHaveMaxSize(max: Int): Field<C> {
        if (value.size > max) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must have at most $max elements (current: ${value.size})",
                ),
            )
        }
        return this
    }

    fun <T, C : Collection<T>> Field<C>.shouldNotBeEmpty(message: String = "must not be empty"): Field<C> {
        if (value.isEmpty()) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = "[]",
                    reason = message,
                ),
            )
        }
        return this
    }

    // ============ Custom Validations ============
    infix fun <T> Field<T>.shouldBeOneOf(options: Collection<T>): Field<T> {
        if (value !in options) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "must be one of: ${options.joinToString(", ")}",
                ),
            )
        }
        return this
    }

    infix fun <T> Field<T>.shouldSatisfy(predicate: (T) -> Boolean): Field<T> {
        if (!predicate(value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = "does not satisfy the required condition",
                ),
            )
        }
        return this
    }

    fun <T> Field<T>.shouldSatisfy(message: String, predicate: (T) -> Boolean): Field<T> {
        if (!predicate(value)) {
            errors.add(
                ExceptionArgument(
                    fieldName = name,
                    value = value.toString(),
                    reason = message,
                ),
            )
        }
        return this
    }

    fun addError(fieldName: String, value: String, reason: String) {
        errors.add(ExceptionArgument(fieldName, value, reason))
    }

    fun hasErrors(): Boolean = errors.isNotEmpty()

    fun getErrors(): List<ExceptionArgument> = errors.toList()

    fun validate(className: String = "") {
        if (errors.isNotEmpty()) {
            throw when {
                className.isBlank() -> {
                    ValidationException(
                        details = errors,
                        errorCode = CommonErrorCode.VALIDATION_FAILED,
                    )
                }

                else -> {
                    ValidationExceptionWithName(
                        className = className,
                        details = errors,
                        errorCode = CommonErrorCode.VALIDATION_FAILED,
                    )
                }
            }
        }
    }
}

// Entrypoint
fun validate(className: String = "", block: ValidationBuilder.() -> Unit) {
    ValidationBuilder().apply(block).validate(className = className)
}

// Entrypoint
fun validateAndReturn(className: String = "", block: ValidationBuilder.() -> Unit): List<ExceptionArgument> =
    ValidationBuilder().apply(block).getErrors()
