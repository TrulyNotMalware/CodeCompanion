package dev.notypie.domain.command

enum class CommandStatus {
    INITIALIZED,
    QUEUED,
    SUCCESS,
    //Failed
    UNAUTHORIZED,
}