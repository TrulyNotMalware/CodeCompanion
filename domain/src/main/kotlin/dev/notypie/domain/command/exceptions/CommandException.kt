package dev.notypie.domain.command.exceptions

import dev.notypie.domain.common.error.CodeCompanionRuntimeException
import dev.notypie.domain.common.error.ErrorCode
import dev.notypie.domain.common.error.ExceptionArgument

internal enum class CommandErrorCode(
    override val message: String,
) : ErrorCode {
    SUBCOMMAND_NOT_VALID(message = "Subcommand not valid."),
    SUBCOMMAND_NOT_FOUND(message = "Subcommand not found."),
    UNSUPPORTED_COMMAND_TYPE(message = "Unsupported command type."),
}

internal sealed class CommandException(
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CodeCompanionRuntimeException(
        errorCode = errorCode,
        details = details,
    )

internal class SubCommandParseException(
    val commandName: String,
    val subCommandName: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CommandException(
        errorCode = errorCode,
        details = details,
    )

internal class UnSupportedCommandException(
    val commandType: String,
    errorCode: ErrorCode,
    details: List<ExceptionArgument>,
) : CommandException(
        errorCode = errorCode,
        details = details,
    )
