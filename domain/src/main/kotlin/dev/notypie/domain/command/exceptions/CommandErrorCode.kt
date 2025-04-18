package dev.notypie.domain.command.exceptions

import dev.notypie.domain.common.error.ErrorCode

enum class CommandErrorCode(
    override val statusCode: Int,
    override val message: String
): ErrorCode{

    COMMAND_NOT_FOUND(
        500,
        "Command not found."
    )
}