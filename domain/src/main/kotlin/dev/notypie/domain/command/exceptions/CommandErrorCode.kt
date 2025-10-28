package dev.notypie.domain.command.exceptions

import dev.notypie.domain.common.error.ErrorCode

enum class CommandErrorCode(
    override val statusCode: Int,
    override val message: String,
) : ErrorCode {
    COMMAND_NOT_FOUND(
        statusCode = 500,
        message = "Command not found.",
    ),

    SUBCOMMAND_NOT_VALID(
        statusCode = 500,
        message = "Subcommand not valid.",
    ),

    SUBCOMMAND_NOT_FOUND(
        statusCode = 404,
        message = "Subcommand not found.",
    ),

    UNKNOWN_SUBCOMMAND_TYPE(
        statusCode = 500,
        message = "Unknown subcommand type.",
    ),
}
