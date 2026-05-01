package dev.notypie.domain.command.entity

internal enum class CommandSet {
    UNKNOWN,
    NOTICE,
    APPROVAL,
    HELP,
    STATUS,
    ;

    companion object {
        fun parseCommand(stringCommand: String) =
            runCatching { valueOf(stringCommand.uppercase()) }.getOrElse { UNKNOWN }
    }
}
