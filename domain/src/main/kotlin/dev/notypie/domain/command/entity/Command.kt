package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.context.SlackChallengeContext
import dev.notypie.domain.command.entity.context.SlackAppMentionContext
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import java.util.*

class Command(
    val appName: String,
    private val commandData: SlackCommandData,
    private val slackApiRequester: SlackApiRequester,
) {
    companion object{
        const val baseUrl: String = "https://slack.com/api/"
    }

    private val commandId: UUID = this.generateIdValue()
    private val commandContext: CommandContext = this.buildContext(this.commandData)

    fun handleEvent(): SlackApiResponse{
        return this.commandContext.runCommand()
    }

    private fun generateIdValue(): UUID = UUID.randomUUID()

    private fun buildContext(commandData: SlackCommandData): CommandContext {
        return when(commandData.slackCommandType){
            SlackCommandType.URL_VERIFICATION -> SlackChallengeContext(
                UrlVerificationRequest(type = commandData.slackCommandType.toString(),
                    channel = commandData.channel,
                    challenge = commandData.rawBody["challenge"].toString(),
                    token = commandData.appToken
                ), slackApiRequester = this.slackApiRequester
            )
            SlackCommandType.EVENT_CALLBACK -> this.handleEventCallBackContext(commandData = commandData)
            else -> this.handleNotSupportedCommand()
        }
    }

    private fun handleEventCallBackContext( commandData: SlackCommandData ): CommandContext {
        val eventCallBack = commandData.body as SlackEventCallBackRequest
        val type = SlackCommandType.valueOf(eventCallBack.event.type.uppercase())
        return when(type){
            SlackCommandType.APP_MENTION -> SlackAppMentionContext(
                slackCommandData = commandData, baseUrl = baseUrl,
                slackApiRequester = this.slackApiRequester, commandId = this.commandId )
            else -> handleNotSupportedCommand()
        }
    }

    private fun handleNotSupportedCommand(): SlackTextResponseContext = SlackTextResponseContext(
        channel = this.commandData.channel, appToken = this.commandData.appToken, requestHeaders = this.commandData.rawHeader,
        slackApiRequester = this.slackApiRequester, text = "Command Not supported."
    )
}