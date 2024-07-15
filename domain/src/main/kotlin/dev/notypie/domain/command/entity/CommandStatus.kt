package dev.notypie.domain.command.entity

enum class CommandStatus {
    INITIALIZED,
    QUEUED,
    SUCCESS,
    //Failed
    UNAUTHORIZED,
}