package dev.notypie.domain.common.error

interface ErrorCode {
    val statusCode: Int
    val message: String
}

internal enum class CommonErrorCode(
    override val statusCode: Int,
    override val message: String,
) : ErrorCode {
    VALIDATION_FAILED(statusCode = 400, message = "Validation failed"),
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

internal sealed class ErrorResponse(
    errorCode: ErrorCode,
    val code: Int = errorCode.statusCode,
    val message: String = errorCode.message,
    val detail: List<ExceptionArgument> = emptyList(),
)

// Base Exception
abstract class CodeCompanionRuntimeException(
    errorCode: ErrorCode,
    val details: List<ExceptionArgument> = emptyList(),
) : RuntimeException(errorCode.message)
