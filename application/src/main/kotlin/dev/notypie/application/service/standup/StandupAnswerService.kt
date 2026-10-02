package dev.notypie.application.service.standup

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.repository.standup.AnswerRecordResult
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private val answerLog = KotlinLogging.logger {}

internal const val SUBMITTED_NOTICE: String = "Standup submitted."
internal const val CLOSED_NOTICE: String =
    "This standup has already closed, so your answer was not recorded or added to the summary."
internal const val SESSION_NOT_FOUND_NOTICE: String =
    "This standup could no longer be found, so your answer was not recorded."

@Service
class StandupAnswerService(
    private val standupRepository: StandupRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val clock: Clock,
) {
    @EventListener
    fun recordAnswer(event: RecordStandupAnswerEvent) {
        val payload = event.payload
        val result =
            standupRepository.recordAnswer(
                sessionUid = payload.sessionUid,
                userId = payload.userId,
                responses = payload.responses,
                submittedAt = clock.instant(),
            )
        val noticeText =
            when (result) {
                AnswerRecordResult.RECORDED -> SUBMITTED_NOTICE

                AnswerRecordResult.SESSION_CLOSED -> {
                    answerLog.info {
                        "Standup answer rejected; session closed: sessionUid=${payload.sessionUid} " +
                            "userId=${payload.userId}"
                    }
                    CLOSED_NOTICE
                }

                AnswerRecordResult.SESSION_NOT_FOUND -> {
                    answerLog.warn { "Standup answer ignored; session not found: sessionUid=${payload.sessionUid}" }
                    SESSION_NOT_FOUND_NOTICE
                }
            }
        val notice = payload.notice ?: return
        val basicInfo =
            CommandBasicInfo.forOutbound(
                publisherId = payload.userId,
                channel = notice.conversation.id,
                idempotencyKey = event.idempotencyKey,
            )
        outboundStager
            .stage(
                message =
                    OutboundMessage.UpdateMessage(
                        ref = notice,
                        content = MessageContent.Text(headline = null, markdown = noticeText),
                        detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    @EventListener
    @Transactional
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
