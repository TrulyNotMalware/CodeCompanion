package dev.notypie.domain.common.error


interface ErrorCode {
    val statusCode: Int
    val message: String
}

data class ExceptionArgument(
    val fieldName: String,
    val value: String,
    val reason: String
)

sealed class ErrorResponse(
    errorCode: ErrorCode,
    val code: Int = errorCode.statusCode,
    val message: String = errorCode.message,
    val detail: List<ExceptionArgument> = emptyList()
)


sealed class CodeCompanionRuntimeException(
    val errorCode: ErrorCode,
    val details: List<ExceptionArgument> = emptyList()
): RuntimeException(errorCode.message)