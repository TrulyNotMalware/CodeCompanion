package dev.notypie.domain.command

enum class CommandSet {
    UNKNOWN, NOTICE;

    companion object{
        fun parseCommand(stringCommand: String) =
            when (stringCommand.uppercase()) {
                "NOTICE" -> NOTICE
                else -> UNKNOWN
            }
    }
}