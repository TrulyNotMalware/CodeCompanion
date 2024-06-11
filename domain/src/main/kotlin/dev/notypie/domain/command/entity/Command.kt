package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.CommandData
import java.util.*

class Command(
    val appId: String,
    val appName: String,

    val commandType: CommandType,
    val publisherId: String,

    val commandContext: CommandContext
) {
    companion object{
        const val baseUrl: String = "https://slack.com/api/"
    }

    val commandId: UUID = this.generateIdValue()


    private fun generateIdValue(): UUID = UUID.randomUUID()

    private fun buildContext(commandData: CommandData) {

    }

    private fun broadcastBotResponseToChannel() {
        this.commandContext.responseBuilder
    }
}