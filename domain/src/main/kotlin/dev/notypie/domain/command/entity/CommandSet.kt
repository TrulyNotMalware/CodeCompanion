package dev.notypie.domain.command.entity

import dev.notypie.domain.command.authorization.CommandPermission

internal enum class CommandSet(
    val requiredPermission: CommandPermission,
) {
    // Free text falls through to the AI assistant, so it is gated like `ask`.
    UNKNOWN(CommandPermission.AI),
    NOTICE(CommandPermission.OPERATIONS),
    APPROVAL(CommandPermission.BASIC),
    HELP(CommandPermission.BASIC),
    STATUS(CommandPermission.OPERATIONS),
    ASK(CommandPermission.AI),
    GRANT(CommandPermission.ADMINISTRATION),
    REVOKE(CommandPermission.ADMINISTRATION),
    ROLES(CommandPermission.ADMINISTRATION),
    CVE(CommandPermission.ADMINISTRATION),
    ;

    companion object {
        fun parseCommand(stringCommand: String) =
            runCatching { valueOf(stringCommand.uppercase()) }.getOrElse { UNKNOWN }
    }
}
