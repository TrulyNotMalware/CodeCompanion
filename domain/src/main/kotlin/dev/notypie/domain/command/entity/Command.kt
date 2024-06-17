package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackCommandData
import java.util.*

class Command(
    val appId: String,
    val appName: String,

    val publisherId: String,
    val commandData: SlackCommandData
) {
    companion object{
        const val baseUrl: String = "https://slack.com/api/"
    }
    val commandId: UUID
//    val commandContext: CommandContext

    init {
        this.commandId = this.generateIdValue()
//        this.commandContext = this.buildContext(this.commandData)
    }

    private fun generateIdValue(): UUID = UUID.randomUUID()

//    private fun buildContext(commandData: SlackCommandData): CommandContext {
    private fun buildContext(commandData: SlackCommandData) {

    }

    private fun broadcastBotResponseToChannel() {
//        this.commandContext.responseBuilder.sendSlackResponse()
    }
}