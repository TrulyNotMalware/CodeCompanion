package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandStatus

// If PIPELINE and SCHEDULED
class CommandHistory(
    val status: CommandStatus = CommandStatus.INITIALIZED


) {
}