package dev.notypie.application.service.calendar

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef

internal const val CALENDAR_HEADLINE = "CodeCompanion — Google Calendar"

internal fun OutboundMessageStager.stageCalendarEphemeral(
    text: String,
    basicInfo: CommandBasicInfo,
    publisher: EventPublisher,
) {
    val staged =
        checkNotNull(
            stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = UserRef(id = basicInfo.publisherId),
                        content = MessageContent.Text(headline = CALENDAR_HEADLINE, markdown = text),
                        detailType = CommandDetailType.CALENDAR_CONNECTION,
                    ),
                basicInfo = basicInfo,
            ),
        ) { "Calendar reply failed to stage an outbox event" }
    publisher.publishOne(event = staged)
}

internal fun OutboundMessageStager.stageCalendarDirectMessage(
    userId: String,
    text: String,
    appId: String,
    publisher: EventPublisher,
) {
    val basicInfo = CommandBasicInfo.forOutbound(publisherId = userId, channel = userId, appId = appId)
    val staged =
        checkNotNull(
            stage(
                message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = userId),
                        content = MessageContent.Text(headline = CALENDAR_HEADLINE, markdown = text),
                        detailType = CommandDetailType.CALENDAR_CONNECTION,
                    ),
                basicInfo = basicInfo,
            ),
        ) { "Calendar DM failed to stage an outbox event" }
    publisher.publishOne(event = staged)
}
