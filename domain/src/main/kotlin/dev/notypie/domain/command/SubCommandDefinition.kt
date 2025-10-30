package dev.notypie.domain.command

interface SubCommandDefinition {
    val subCommandIdentifier: String
    val requiresArguments: Boolean
    val minRequiredArgs: Int
    val usage: String

    fun validateArguments(subCommands: List<String>): Boolean =
        !requiresArguments || subCommands.size >= minRequiredArgs
}

class NoSubCommands(
    override val subCommandIdentifier: String = "",
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
    override val usage: String = "",
) : SubCommandDefinition

internal data class SubCommand<out T : SubCommandDefinition>(
    val subCommandDefinition: T,
    val options: List<String> = listOf(),
) {
    companion object {
        fun empty(): SubCommand<NoSubCommands> = SubCommand(subCommandDefinition = NoSubCommands())

        fun <T : SubCommandDefinition> of(definition: T, options: List<String> = listOf()) =
            SubCommand(
                subCommandDefinition = definition,
                options = options,
            )
    }

    fun isValid() = subCommandDefinition.validateArguments(subCommands = options)
}

internal inline fun <reified T> findSubCommandByIdentifier(
    identifier: String,
): T?
        where T : Enum<T>, T : SubCommandDefinition =
    enumValues<T>().find { it.subCommandIdentifier == identifier }
