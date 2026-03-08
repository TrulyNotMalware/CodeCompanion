package dev.notypie.domain.command.exceptions

import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.common.error.ErrorCode
import dev.notypie.domain.common.error.ExceptionArgument

sealed class CommandException(
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )

class SubCommandParseException(
    val commandName: String,
    val subCommandName: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CommandException(
        errorCode = errorCode,
        details = details,
    )

class UnSupportedCommandException(
    val commandType: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CommandException(
        errorCode = errorCode,
        details = details,
    )

class ValidationException(
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )

class ValidationExceptionWithName(
    val className: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument> = emptyList(),
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )
