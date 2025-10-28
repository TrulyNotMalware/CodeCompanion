package dev.notypie.domain.command.exceptions

import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.common.error.ErrorCode
import dev.notypie.domain.common.error.ExceptionArgument

abstract class CommandException(
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
