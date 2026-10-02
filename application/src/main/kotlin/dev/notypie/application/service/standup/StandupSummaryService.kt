package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.entity.enums.SessionStatus
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

@Service
class StandupSummaryService(
    private val standupRepository: StandupRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    // The session is read in the transaction that saves the summary and flips it SUMMARIZED, under a row lock that
    // recordAnswer also takes: an answer acknowledged as submitted is in this summary, and one that waits on the lock
    // finds the session closed. Read outside, an answer committed between the read and the CAS was lost (review G5).
    @EventListener
    fun postSummary(event: StandupCutoffEvent) {
        transactionTemplate
            .runInTx {
                val session =
                    standupRepository.findSessionForSummary(sessionUid = event.sessionUid)
                        ?: run {
                            summaryLog.warn {
                                "Standup summary skipped; session not found: sessionUid=${event.sessionUid}"
                            }
                            return@runInTx false
                        }
                if (session.status != SessionStatus.COLLECTING) {
                    summaryLog.info {
                        "Standup summary skipped; session is ${session.status}: sessionUid=${event.sessionUid}"
                    }
                    return@runInTx false
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
                                        answers = session.answers,
                                        questions = routine.questions,
                                    ),
                            ),
                        basicInfo = commandBasicInfo,
                    )
                outboxRepository.save(summaryRow)
                if (!standupRepository.markSessionSummarized(
                        sessionId = session.sessionId,
                        messageTs = "outbox:${summaryRow.eventId}",
                    )
                ) {
                    error("Session was already summarized: sessionUid=${session.sessionUid}")
                }
                true
            }.onSuccess { enqueued ->
                if (enqueued) summaryLog.info { "Standup summary enqueued: sessionUid=${event.sessionUid}" }
            }.onFailure { ex ->
                summaryLog.warn(ex) { "Standup summary enqueue rolled back: sessionUid=${event.sessionUid}" }
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
