package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.toOutboxMessage
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
    private val slackEventBuilder: SlackApiEventConstructor,
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
        val summaryEvent =
            slackEventBuilder.standupSummaryRequest(
                commandBasicInfo = commandBasicInfo,
                routineName = routine.name,
                sessionDate = session.sessionDate,
                members = routine.members,
                answers = session.answers,
                questions = routine.questions,
            )
        val summaryMarker = "outbox:${summaryEvent.payload.eventId}"
        transactionTemplate
            .runInTx<Unit> {
                outboxRepository.save(summaryEvent.toOutboxMessage())
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

    /**
     * Listens to every successful outbox publish and tries to swap the temporary
     * `outbox:<eventId>` marker stored on the session row with the actual Slack `ts` returned
     * by `chat.postMessage`. The UPDATE is keyed on `summary_message_ts = marker`, so it is a
     * no-op for non-standup events and survives restarts: the marker is durable in the DB,
     * not in JVM-local state, so a relay-after-restart will still find the row to update.
     *
     * The cost is one indexed UPDATE per published outbox row, accepted because (a) the
     * keying column is the marker itself (no row scanned for non-matches) and (b) attaching
     * `commandDetailType` to [MessagePublishSuccessEvent] just to short-circuit here would
     * leak summary-flow concerns into the generic relay event.
     */
    @EventListener
    fun replaceSummaryMarkerWithSlackTs(event: MessagePublishSuccessEvent) {
        if (event.messageTs.isBlank()) return
        val marker = "outbox:${event.eventId}"
        if (standupRepository.replaceSummaryMessageTs(currentMessageTs = marker, messageTs = event.messageTs)) {
            summaryLog.info { "Standup summary Slack ts recorded: eventId=${event.eventId}" }
        }
    }
}
