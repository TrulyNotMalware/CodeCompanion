package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.entity.context.SlackChallengeContext
import dev.notypie.domain.command.entity.context.SlackAppMentionContext
import java.util.*

class Command(
    val appId: String,
    val appName: String,

    val publisherId: String,
    val commandData: SlackCommandData,
    val commandContext: CommandContext
) {
    companion object{
        const val baseUrl: String = "https://slack.com/api/"
    }
    val commandId: UUID

    init {
        this.commandId = this.generateIdValue()
    }

    private fun generateIdValue(): UUID = UUID.randomUUID()

    private fun buildContext(commandData: SlackCommandData): CommandContext {
        return when(commandData.slackCommandType){
            SlackCommandType.URL_VERIFICATION -> SlackChallengeContext(
                UrlVerificationRequest(type = commandData.slackCommandType.toString(),
                    channel = commandData.channel,
                    challenge = commandData.rawBody["challenge"].toString(),
                    token = commandData.appToken)
            )
            SlackCommandType.EVENT_CALLBACK -> {
                if(commandData.rawBody.containsKey("event") && commandData.rawBody["event"] is Map<*, *>){
                    val eventBody:Map<String, Any> = commandData.rawBody["event"] as Map<String, Any>
                    return when(eventBody["type"]){
//                        "app_mention" -> SlackAppMentionContext()
                        else -> TODO()
                    }
                } else {
                    throw RuntimeException("NOT VALID REQUEST") //TODO fix this exception later.
                }
            }
            else -> TODO()
        }
    }

    private fun broadcastBotResponseToChannel() {
//        this.commandContext.sendSlackResponse()
    }
}