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
    // Application information
    val apiAppId: String,
    val botId: String,
    val token: String,
    val container: Container,
    val channel: Channel,
    val responseUrl: String,
    val states: List<States>,
    val currentAction: States,
    /**
     * Extra routing tokens decoded from the embedded message-text payload after
     * `idempotencyKey` and `commandDetailType`. Non-null: empty list means no extra
     * tokens were present (standard 2-token interaction). Feature contexts that
     * need extra routing context (e.g. a meetingId for participant notices) read
     * the first element of this list and parse it into their own typed value.
     */
    val routingExtras: List<String> = emptyList(),
)

fun InteractionPayload.isCompleted(): Boolean =
    currentAction.type.isPrimary &&
        currentAction.isSelected &&
        states.all {
            it.isSelected ||
                it.type == ActionElementTypes.CHECKBOX || // Checkbox is considered true
                it.type == ActionElementTypes.PLAIN_TEXT_INPUT // PlainTextInput considered true
        }

fun InteractionPayload.isPrimary() = currentAction.type.isPrimary

fun InteractionPayload.isCanceled() = currentAction.type == ActionElementTypes.REJECT_BUTTON

fun InteractionPayload.toSlackCommandData(
    rawBody: Map<String, Any> = mapOf(),
    rawHeader: SlackRequestHeaders = SlackRequestHeaders(),
) = SlackCommandData(
    appId = apiAppId,
    appToken = token,
    publisherId = user.id,
    publisherName = user.name,
    channel = channel.id,
    channelName = channel.name,
    body = this@toSlackCommandData,
    slackCommandType = SlackCommandType.INTERACTION_RESPONSE,
    rawBody = rawBody,
    rawHeader = rawHeader,
)
