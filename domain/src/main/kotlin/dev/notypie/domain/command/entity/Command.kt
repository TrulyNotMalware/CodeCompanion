package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
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

    private fun parseSlackEventFromRequest(
        headers: Map<String, List<String>>,
        payload: Map<String, Any>
    ) {

    }

    private fun broadcastBotResponseToChannel() {

    }
}