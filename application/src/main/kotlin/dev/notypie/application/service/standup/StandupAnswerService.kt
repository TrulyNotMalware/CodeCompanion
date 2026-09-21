package dev.notypie.application.service.standup

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock

private val answerLog = KotlinLogging.logger {}

@Service
class StandupAnswerService(
    private val standupRepository: StandupRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @EventListener
    fun recordAnswer(event: RecordStandupAnswerEvent) {
        val payload = event.payload
        val updated =
            standupRepository.recordAnswer(
                sessionUid = payload.sessionUid,
                userId = payload.userId,
                responses = payload.responses,
                submittedAt = clock.instant(),
            )
        if (!updated) {
            answerLog.warn { "Standup answer ignored; session not found: sessionUid=${payload.sessionUid}" }
        }
    }

    @EventListener
    fun onStandupModalOpenFailed(event: StandupModalOpenFailedEvent) {
        answerLog.warn {
            "views.open fallback triggered for standup: userId=${event.userId} " +
                "channel=${event.channel} reason=${event.reason}"
        }
        val basicInfo =
            CommandBasicInfo.forOutbound(
                appId = event.apiAppId,
                publisherId = event.userId,
                channel = event.channel,
                idempotencyKey = event.idempotencyKey,
            )
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = basicInfo.channel),
                        recipient = null,
                        content =
                            MessageContent.Text(
                                headline = null,
                                markdown =
                                    "Couldn't open the standup form. _Tip: re-click the *Fill in standup* button " +
                                        "from the original DM to try again._",
                            ),
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }
}
