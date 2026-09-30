package dev.notypie.domain.common

import dev.notypie.domain.common.error.CommonErrorCode
import dev.notypie.domain.common.error.ExceptionArgument
import dev.notypie.domain.common.error.ValidationException
import dev.notypie.domain.common.error.ValidationExceptionWithName
import java.time.LocalDateTime
import java.util.Collections
import java.util.IdentityHashMap

class ValidationBuilder {
    private val errors = mutableListOf<ExceptionArgument>()

    class Field<T> internal constructor(
        val name: String,
        val value: T,
        internal val raisedErrors: MutableSet<ExceptionArgument>,
    )

    private fun <T> field(name: String, value: T): Field<T> =
        Field(name = name, value = value, raisedErrors = identitySet())

    // A block gets a child Field with its own error set, so an `or` inside it sees only the block's errors
    // as its left operand; claimErrorsOf merges the block's surviving errors into the parent afterwards.
    private fun <T> Field<*>.nested(value: T): Field<T> =
        Field(name = name, value = value, raisedErrors = identitySet())

    private fun identitySet(): MutableSet<ExceptionArgument> = Collections.newSetFromMap(IdentityHashMap())

    private fun Field<*>.reject(error: ExceptionArgument) {
        errors.add(error)
        raisedErrors.add(error)
    }

    private fun errorsAddedBy(block: () -> Unit): List<ExceptionArgument> {
        val before = identitySet().apply { addAll(errors) }
        block()
        return errors.filterNot { it in before }
    }

    private fun Field<*>.claimErrorsOf(block: () -> Unit) {
        raisedErrors.addAll(errorsAddedBy(block = block))
    }

    private fun discard(discarded: Collection<ExceptionArgument>) {
        val targets = identitySet().apply { addAll(discarded) }
        errors.removeAll { it in targets }
    }

    infix fun <T> String.of(value: T): Field<T> = field(name = this, value = value)

    infix fun <T> Field<T>.and(block: ValidationBuilder.(Field<T>) -> Unit): Field<T> {
        claimErrorsOf { block(nested(value = value)) }
        return this
    }

    infix fun <T> Field<T>.or(block: ValidationBuilder.(Field<T>) -> Unit): Field<T> {
        val leftErrors = raisedErrors.toList()
        val rightErrors = errorsAddedBy { block(nested(value = value)) }
        val cleared = if (rightErrors.isEmpty()) leftErrors else rightErrors
        discard(discarded = cleared)
        cleared.forEach { raisedErrors.remove(it) }
        return this
    }

    infix fun <T> Field<T?>.shouldNotBeNullAnd(block: ValidationBuilder.(Field<T>) -> Unit): Field<T?> {
        if (value == null) {
            reject(
                error =
                    ExceptionArgument(
                        fieldName = name,
                        value = "null",
                        reason = "must not be null",
                    ),
            )
        } else {
            @Suppress("UNCHECKED_CAST")
            claimErrorsOf { block(nested(value = value as T)) }
        }
        return this
    }

    infix fun <T> Field<T?>.ifNotNull(block: ValidationBuilder.(Field<T>) -> Unit): Field<T?> {
        if (value != null) {
            @Suppress("UNCHECKED_CAST")
            claimErrorsOf { block(nested(value = value as T)) }
        }
        return this
    }

    fun notBlank(block: StringFieldsBuilder.() -> Unit) {
        val builder = StringFieldsBuilder()
        builder.block()
        builder.fields.forEach { (name, value) ->
            if (value.isBlank()) {
                errors.add(
                    ExceptionArgument(
                        fieldName = name,
                        value = value,
                        reason = "must not be blank",
                    ),
                )
            }
        }
    }

    class StringFieldsBuilder {
        val fields = mutableListOf<Pair<String, String>>()

        infix fun String.of(value: String) {
            fields.add(this to value)
        }
    }

    infix fun Field<String>.shouldBeShorterThan(max: Int): Field<String> {
        if (value.length > max) {
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
                    ExceptionArgument(
                        fieldName = name,
                        value = value ?: "null",
                        reason = "must not be null or blank",
                    ),
            )
        }
        return this
    }

    infix fun Field<Int>.shouldBeLessThan(max: Int): Field<Int> {
        if (value >= max) {
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
                    ExceptionArgument(
                        fieldName = name,
                        value = value.toString(),
                        reason = "must be between ${range.first} and ${range.last}",
                    ),
            )
        }
        return this
    }

    fun Field<Int>.shouldBePositive(): Field<Int> {
        if (value <= 0) {
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
                    ExceptionArgument(
                        fieldName = name,
                        value = value.toString(),
                        reason = "must be non-negative",
                    ),
            )
        }
        return this
    }

    infix fun Field<LocalDateTime>.shouldBeAfter(other: LocalDateTime): Field<LocalDateTime> {
        if (!value.isAfter(other)) {
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
                    ExceptionArgument(
                        fieldName = name,
                        value = value.toString(),
                        reason = "must be in the past",
                    ),
            )
        }
        return this
    }

    infix fun <T, C : Collection<T>> Field<C>.shouldHaveSize(size: Int): Field<C> {
        if (value.size != size) {
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
                    ExceptionArgument(
                        fieldName = name,
                        value = "[]",
                        reason = message,
                    ),
            )
        }
        return this
    }

    infix fun <T> Field<T>.shouldBeOneOf(options: Collection<T>): Field<T> {
        if (value !in options) {
            reject(
                error =
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
            reject(
                error =
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
            reject(
                error =
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
        errors.add(ExceptionArgument(fieldName = fieldName, value = value, reason = reason))
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

fun validate(className: String = "", block: ValidationBuilder.() -> Unit) {
    ValidationBuilder().apply(block).validate(className = className)
}

internal fun validateAndReturn(className: String = "", block: ValidationBuilder.() -> Unit): List<ExceptionArgument> =
    ValidationBuilder().apply(block).getErrors()
