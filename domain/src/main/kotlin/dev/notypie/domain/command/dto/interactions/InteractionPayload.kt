package dev.notypie.domain.command.dto.interactions

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType

// Reference from Slack SDK - BlockActionPayload
data class InteractionPayload(
    val type: CommandDetailType,
    val team: Team,
    val user: User,
    val triggerId: String,
    val isEnterprise: Boolean,
    val enterprise: Enterprise? = null,
    val idempotencyKey: String,

    //Application information
    val apiAppId: String,
    val botId: String,
    val token: String,
    val container: Container,
    val channel: Channel,

    val responseUrl: String,
    val states: List<States>,
    val currentAction: States
)

fun InteractionPayload.isCompleted(): Boolean =
    this.currentAction.type.isPrimary &&
    this.currentAction.isSelected &&
    this.states.all { it.isSelected ||
            it.type == ActionElementTypes.CHECKBOX ||//Checkbox is considered true
            it.type == ActionElementTypes.PLAIN_TEXT_INPUT }//PlainTextInput considered true

fun InteractionPayload.isPrimary() = this.currentAction.type.isPrimary
fun InteractionPayload.isCanceled() = this.currentAction.type == ActionElementTypes.REJECT_BUTTON

fun InteractionPayload.toSlackCommandData(
    rawBody: Map<String, Any> = mapOf(), rawHeader: SlackRequestHeaders = SlackRequestHeaders()
) =
    SlackCommandData(
        appId = this.apiAppId,
        appToken = this.token,
        publisherId = this.user.id,
        publisherName = this.user.name,
        channel = this.channel.id,
        channelName = this.channel.name,
        body = this,
        slackCommandType = SlackCommandType.INTERACTION_RESPONSE,
        rawBody = rawBody,
        rawHeader = rawHeader
    )