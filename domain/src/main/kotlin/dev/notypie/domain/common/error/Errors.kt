package dev.notypie.domain.common.error

// Transport-neutral on purpose: HTTP status mapping belongs to the application layer's ControllerAdvice.
interface ErrorCode {
    val message: String
}

internal enum class CommonErrorCode(
    override val message: String,
) : ErrorCode {
    VALIDATION_FAILED(message = "Validation failed"),
}

data class ExceptionArgument(
    val fieldName: String,
    val value: String,
    val reason: String = "",
)

fun exceptionDetails(configure: ExceptionDetailsBuilder.() -> Unit): List<ExceptionArgument> =
    ExceptionDetailsBuilder().apply(block = configure).details

class ExceptionDetailsBuilder {
    internal val details = mutableListOf<ExceptionArgument>()

    infix fun String.value(fieldValue: String): ReasonBuilder =
        ReasonBuilder(fieldName = this, fieldValue = fieldValue, parent = this@ExceptionDetailsBuilder)
}

class ReasonBuilder(
    private val fieldName: String,
    private val fieldValue: String,
    private val parent: ExceptionDetailsBuilder,
) {
    infix fun because(reason: String) {
        parent.details.add(
            ExceptionArgument(
                fieldName = fieldName,
                value = fieldValue,
                reason = reason,
            ),
        )
    }
}

abstract class CodeCompanionRuntimeException(
    val errorCode: ErrorCode,
    val details: List<ExceptionArgument> = emptyList(),
) : RuntimeException(errorCode.message)

internal class ValidationException(
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )

internal class ValidationExceptionWithName(
    val className: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument> = emptyList(),
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )
