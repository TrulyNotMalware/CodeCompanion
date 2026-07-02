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
    // Transaction boundary lives on StandupRepositoryImpl.recordAnswer (the read-modify-write
    // over the session's answer collection). This listener only forwards the payload, so it
    // carries no transaction of its own.
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

    /**
     * Fallback path invoked when `views.open` for the standup answer modal fails. Unlike the
     * decline-reason flow there is no provisional persistence to fall back on — the answers
     * exist only in the unopened modal — so the only remediation is to surface an ephemeral
     * notice telling the user the link is still actionable from the original DM. We don't
     * attempt to reopen the modal; the trigger_id is dead by the time this fires.
     */
    @EventListener
    fun onStandupModalOpenFailed(event: StandupModalOpenFailedEvent) {
        answerLog.warn {
            "views.open fallback triggered for standup: userId=${event.userId} " +
                "channel=${event.channel} reason=${event.reason}"
        }
        // `chat.postEphemeral` requires `channel` to be an IM/channel ID and `user` to be the
        // recipient's user ID. The originating DM's channel was already captured on
        // [event.channel] (a D-channel for the bot↔user IM); routing it through CommandBasicInfo
        // — and leaving the recipient null — keeps `channel` and `user` distinct on the wire.
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
