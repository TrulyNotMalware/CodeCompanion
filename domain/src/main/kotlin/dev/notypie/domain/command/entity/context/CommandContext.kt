package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandType
import kotlin.reflect.KClass

abstract class CommandContext(
    val channel: String,
    val appToken: String,
    val tracking: Boolean = true,

    val idempotencyKey: String,
    val requestHeaders: SlackRequestHeaders,
    val slackApiRequester: SlackApiRequester,
) {
    val commandType: CommandType = this.parseCommandType()

    internal abstract fun parseCommandType(): CommandType
    internal open fun runCommand(): SlackApiResponse{
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Hello Developer!",
            channel = this.channel, simpleString = "This is default response.",
            commandType = CommandType.SIMPLE,
            idempotencyKey = this.idempotencyKey
        )
    }
    //TODO() when Approved or when Rejected action defines.
    //NOT IN COMPANION OBJECTS
//    fun doWhenApproved() :SlackCommandData{
//
//    }
}