package dev.notypie.domain.command.exceptions

import dev.notypie.domain.common.error.ErrorCode

internal enum class CommandErrorCode(
    override val message: String,
) : ErrorCode {
    SUBCOMMAND_NOT_VALID(message = "Subcommand not valid."),
    SUBCOMMAND_NOT_FOUND(message = "Subcommand not found."),
    UNSUPPORTED_COMMAND_TYPE(message = "Unsupported command type."),
}
