package dev.notypie.impl.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager

/**
 * Slack adapter that renders the text family of [OutboundMessage]s into staged [CommandEvent]s,
 * reproducing exactly the builder calls the [SlackIntentResolver] makes for the now-removed
 * `TextResponse`/`EphemeralResponse`/`ErrorDetail`/`TimeSchedule`/`Notice` intents. Non-text
 * variants are not migrated yet and fail loudly.
 */
class SlackOutboundStager(
    private val slackEventBuilder: SlackApiEventConstructor,
) : OutboundMessageStager {
    override fun stage(message: OutboundMessage, basicInfo: CommandBasicInfo): CommandEvent<EventPayload>? =
        when (message) {
            is OutboundMessage.ChannelMessage ->
                when (val content = message.content) {
                    is MessageContent.Text ->
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline.orEmpty(),
                            commandBasicInfo = basicInfo,
                            simpleString = content.markdown,
                        )

                    is MessageContent.ErrorNotice ->
                        slackEventBuilder.detailErrorTextRequest(
                            commandDetailType = CommandDetailType.ERROR_RESPONSE,
                            errorClassName = content.className,
                            errorMessage = content.message,
                            details = content.details,
                            commandBasicInfo = basicInfo,
                        )

                    is MessageContent.Schedule ->
                        slackEventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            headLineText = content.headline,
                            commandBasicInfo = basicInfo,
                            timeScheduleInfo = content.info,
                        )

                    else -> error("not yet migrated: $content")
                }

            is OutboundMessage.Ephemeral -> {
                val content = message.content
                check(content is MessageContent.Text) { "Ephemeral content must be Text: $content" }
                slackEventBuilder.simpleEphemeralTextRequest(
                    textMessage = content.markdown,
                    commandBasicInfo = basicInfo,
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    targetUserId = message.recipient?.id,
                )
            }

            is OutboundMessage.Notice -> {
                val userMentions = message.mentions.joinToString(" ") { "<@${it.id}>" }
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    headLineText = "Notice!",
                    commandBasicInfo = basicInfo,
                    simpleString = "[Notice] $userMentions ${message.message}",
                )
            }

            else -> error("OutboundMessage variant not yet migrated to stager: $message")
        }
}
