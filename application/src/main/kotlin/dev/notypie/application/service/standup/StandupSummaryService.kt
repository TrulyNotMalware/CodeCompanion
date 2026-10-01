package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

private val summaryLog = KotlinLogging.logger {}

// The outbox payload column is TEXT (65,535 bytes) and Korean text is 3 bytes a character, so the stored
// summary is bounded; a member's whole answer also has to fit one 3,000-character Slack section when rendered.
internal const val SUMMARY_MEMBER_RESPONSE_CHARS = 2_400
internal const val SUMMARY_TOTAL_RESPONSE_CHARS = 18_000

internal fun List<StandupAnswerDto>.boundedForSummary(): List<StandupAnswerDto> {
    if (isEmpty()) return this
    val perMember = minOf(SUMMARY_MEMBER_RESPONSE_CHARS, SUMMARY_TOTAL_RESPONSE_CHARS / size)
    return map { answer ->
        if (answer.responses.isEmpty()) {
            answer
        } else {
            val perResponse = perMember / answer.responses.size
            answer.copy(
                responses =
                    answer.responses.map { response ->
                        if (response.length <= perResponse) response else "${response.take(perResponse - 1)}…"
                    },
            )
        }
    }
}

@Service
class StandupSummaryService(
    private val standupRepository: StandupRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @EventListener
    fun postSummary(event: StandupCutoffEvent) {
        val session =
            standupRepository.findSession(sessionUid = event.sessionUid)
                ?: run {
                    summaryLog.warn { "Standup summary skipped; session not found: sessionUid=${event.sessionUid}" }
                    return
                }
        val routine = standupRepository.getRoutine(routineUid = event.routineUid)
        val commandBasicInfo =
            CommandBasicInfo.forOutbound(
                publisherId = routine.creatorId,
                channel = routine.summaryChannel,
            )
        val summaryRow =
            outboundMessagePort.toRow(
                message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = commandBasicInfo.channel),
                        content =
                            MessageContent.StandupSummary(
                                routineName = routine.name,
                                sessionDate = session.sessionDate,
                                members = routine.members,
                                answers = session.answers.boundedForSummary(),
                                questions = routine.questions,
                            ),
                    ),
                basicInfo = commandBasicInfo,
            )
        val summaryMarker = "outbox:${summaryRow.eventId}"
        transactionTemplate
            .runInTx<Unit> {
                outboxRepository.save(summaryRow)
                if (!standupRepository.markSessionSummarized(
                        sessionId = session.sessionId,
                        messageTs = summaryMarker,
                    )
                ) {
                    error("Session was already summarized: sessionUid=${session.sessionUid}")
                }
            }.onSuccess {
                summaryLog.info { "Standup summary enqueued: sessionUid=${session.sessionUid}" }
            }.onFailure { ex ->
                summaryLog.warn(ex) { "Standup summary enqueue rolled back: sessionUid=${session.sessionUid}" }
            }
    }

    @EventListener
    fun replaceSummaryMarkerWithSlackTs(event: MessagePublishSuccessEvent) {
        if (event.messageTs.isBlank()) return
        val marker = "outbox:${event.eventId}"
        if (standupRepository.replaceSummaryMessageTs(currentMessageTs = marker, messageTs = event.messageTs)) {
            summaryLog.info { "Standup summary Slack ts recorded: eventId=${event.eventId}" }
        }
    }
}
