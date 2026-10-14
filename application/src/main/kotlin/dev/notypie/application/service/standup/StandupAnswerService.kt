package dev.notypie.application.service.standup

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.intent.StandupAnswerConfirmation
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.outbound.directFallbackOf
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
        val content = MessageContent.Text(headline = null, markdown = noticeText)
        when (val confirmation = payload.confirmation) {
            is StandupAnswerConfirmation.UpdateNotice ->
                stageConfirmation(
                    event = event,
                    channel = confirmation.ref.conversation,
                    message =
                        OutboundMessage.UpdateMessage(
                            ref = confirmation.ref,
                            content = content,
                            detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                        ),
                )

            is StandupAnswerConfirmation.Ephemeral -> {
                val requester = UserRef(id = payload.userId)
                stageConfirmation(
                    event = event,
                    channel = confirmation.channel,
                    message =
                        OutboundMessage.Ephemeral(
                            target = confirmation.channel,
                            recipient = requester,
                            content = content,
                            detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                            fallback =
                                directFallbackOf(
                                    channel = confirmation.channel,
                                    recipient = requester,
                                    content = content,
                                ),
                        ),
                )
            }

            StandupAnswerConfirmation.None -> Unit
        }
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
        val channel = ConversationTarget(id = basicInfo.channel)
        val content =
            MessageContent.Text(
                headline = null,
                markdown =
                    "Couldn't open the standup form. _Tip: re-click the *Fill in standup* button " +
                        "in the original DM, or run `/standup answer` in the routine's channel, " +
                        "to try again._",
            )
        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = channel,
                        recipient = null,
                        content = content,
                        fallback =
                            directFallbackOf(
                                channel = channel,
                                recipient = UserRef(id = event.userId),
                                content = content,
                            ),
                    ),
                basicInfo = basicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    private fun stageConfirmation(
        event: RecordStandupAnswerEvent,
        channel: ConversationTarget,
        message: OutboundMessage,
    ) {
        val basicInfo =
            CommandBasicInfo.forOutbound(
                publisherId = event.payload.userId,
                channel = channel.id,
                idempotencyKey = event.idempotencyKey,
            )
        outboundStager.stage(message = message, basicInfo = basicInfo)?.let { eventPublisher.publishOne(event = it) }
    }
}
