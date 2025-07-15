package dev.notypie.domain.command

internal interface SubCommandDefinition {
    val subCommandIdentifier: String
    val requiresArguments: Boolean
    val minRequiredArgs: Int
    val usage: String

    fun validateArguments(subCommands: List<String>): Boolean =
        !requiresArguments || subCommands.size >= minRequiredArgs
}

internal class NoSubCommands(
    override val subCommandIdentifier: String = "",
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
    override val usage: String = ""
): SubCommandDefinition


internal data class SubCommand(
    val subCommandDefinition: SubCommandDefinition,
    val options: List<String> = listOf()
){
    companion object{
        fun empty() = SubCommand(NoSubCommands())
    }
}

internal inline fun <reified T> findSubCommandByIdentifier(identifier: String): T?
        where T : Enum<T>, T : SubCommandDefinition {
    return enumValues<T>().find { it.subCommandIdentifier == identifier }
}