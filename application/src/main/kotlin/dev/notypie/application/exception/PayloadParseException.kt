package dev.notypie.application.exception

import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.common.error.ErrorCode
import dev.notypie.domain.common.error.ExceptionArgument

enum class PayloadParseErrorCode(
    override val message: String,
) : ErrorCode {
    APP_ID_NOT_FOUND(
        message = "Application ID not found in payload.",
    ),
    UNSUPPORTED_SLACK_COMMAND_TYPE(
        message = "Unsupported Slack command type in payload.",
    ),
    INVALID_EVENT_PAYLOAD(
        message = "Slack event payload does not have the expected shape.",
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

class InvalidEventPayloadException(
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
    cause: Throwable,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    ) {
    init {
        initCause(cause)
    }
}
