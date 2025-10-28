package dev.notypie.domain.command

import dev.notypie.domain.UNKNOWN_SUB_COMMAND_IDENTIFIER

enum class UnknownSubCommandDefinition(
    override val subCommandIdentifier: String = UNKNOWN_SUB_COMMAND_IDENTIFIER,
    override val usage: String,
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
) : SubCommandDefinition {
    UNKNOWN(usage = "unknown command", requiresArguments = false),
    TOO_MANY_ARGUMENTS_SUB_COMMAND(
        usage = "unknown command",
        minRequiredArgs = Int.MAX_VALUE,
    ),
}
