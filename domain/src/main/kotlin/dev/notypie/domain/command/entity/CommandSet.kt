package dev.notypie.domain.command.entity

enum class CommandSet {
    UNKNOWN, NOTICE, APPROVAL;

    companion object{
        fun parseCommand(stringCommand: String) =
            runCatching { valueOf(stringCommand.uppercase()) }.getOrElse { UNKNOWN }
    }
}