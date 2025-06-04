package dev.notypie.domain.command

internal interface SubCommandDefinition {
    val subCommandIdentifier: String
    val requiresArguments: Boolean
    val minRequiredArgs: Int
    val usage: String

    fun validateArguments(subCommands: List<String>): Boolean =
        !requiresArguments || subCommands.size >= minRequiredArgs
}

internal interface SubCommandParser<T : SubCommandDefinition> {
    fun parse(subCommands: List<String>): Pair<T, List<String>>
}