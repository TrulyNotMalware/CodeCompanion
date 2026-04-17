package dev.notypie.application.exception

import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.common.error.ErrorCode
import dev.notypie.domain.common.error.ExceptionArgument

enum class PayloadParseErrorCode(
    override val statusCode: Int,
    override val message: String,
) : ErrorCode {
    APP_ID_NOT_FOUND(
        statusCode = 400,
        message = "Application ID not found in payload.",
    ),
    UNSUPPORTED_SLACK_COMMAND_TYPE(
        statusCode = 400,
        message = "Unsupported Slack command type in payload.",
    ),
}

class AppIdNotFoundException(
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )

class UnsupportedSlackCommandTypeException(
    val rawCommandType: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )
