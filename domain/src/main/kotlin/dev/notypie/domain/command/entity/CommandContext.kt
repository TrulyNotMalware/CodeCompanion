package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders

abstract class CommandContext(
    val channel: String,
    val botToken: String,
    val tracking: Boolean = true,

    val requestHeaders: SlackRequestHeaders,
    val responseBuilder: SlackResponseBuilder
) {
    val commandType: CommandType

    init{
        this.commandType = this.parseCommandType()
    }

    abstract fun parseCommandType(): CommandType

    private fun buildContext(){

    }
}