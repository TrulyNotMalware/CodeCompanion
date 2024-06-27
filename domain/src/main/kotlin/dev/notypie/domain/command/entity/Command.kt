package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.entity.context.SlackChallengeContext
import dev.notypie.domain.command.entity.context.SlackAppMentionContext
import java.util.*

class Command(
    val appId: String,
    val appName: String,

    val publisherId: String,
    private val commandData: SlackCommandData,
    private val slackRequestHandler: SlackRequestHandler,
    private val slackResponseBuilder: SlackRequestBuilder,
) {
    companion object{
        const val baseUrl: String = "https://slack.com/api/"
    }

    private val commandId: UUID = this.generateIdValue()
    private val commandContext: CommandContext = this.buildContext(this.commandData)

    private fun generateIdValue(): UUID = UUID.randomUUID()

    private fun buildContext(commandData: SlackCommandData): CommandContext {
        return when(commandData.slackCommandType){
            SlackCommandType.URL_VERIFICATION -> SlackChallengeContext(
                UrlVerificationRequest(type = commandData.slackCommandType.toString(),
                    channel = commandData.channel,
                    challenge = commandData.rawBody["challenge"].toString(),
                    token = commandData.appToken
                ), responseBuilder = this.slackResponseBuilder, requestHandler = slackRequestHandler
            )
            SlackCommandType.EVENT_CALLBACK -> {
                return this.handleEventCallBackContext(
                    commandData = commandData
                )
            }
            else -> TODO()
        }
    }

    private fun handleEventCallBackContext( commandData: SlackCommandData ): CommandContext {
        return when(commandData.slackCommandType){
            SlackCommandType.APP_MENTION -> SlackAppMentionContext(
                slackCommandData = commandData, baseUrl = baseUrl,
                responseBuilder = this.slackResponseBuilder, requestHandler = slackRequestHandler )
            else -> TODO()
        }
    }

    fun broadcastBotResponseToChannel() {
//        this.commandContext.sendSlackResponse()
    }
}