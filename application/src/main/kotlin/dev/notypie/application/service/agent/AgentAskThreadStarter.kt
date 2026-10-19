package dev.notypie.application.service.agent

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.relay.STATUS_WRITE_ATTEMPTS
import dev.notypie.common.containFailure
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentConversePayload
import dev.notypie.domain.command.entity.event.AgentConverseRequestEvent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.agent.AgentAskTurnRepository
import dev.notypie.repository.agent.RecordedAskTurn
import dev.notypie.repository.agent.recordDelivered
import dev.notypie.repository.agent.schema.AgentAskTurnSchema
import dev.notypie.repository.agent.schema.AgentAskTurnStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.OutboundMessageCodecException
import dev.notypie.repository.outbox.dto.MessageDeliveredEvent
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

internal const val ASK_RESUME_PERIOD_MILLIS = 60_000L
internal const val ASK_RESUME_BATCH_SIZE = 20
internal const val ASK_UNREADABLE_LOOK_AHEAD_MAX = 4 * ASK_RESUME_BATCH_SIZE
internal const val ASK_MAX_RUNS = 5
internal val ASK_PENDING_GRACE: Duration = Duration.ofSeconds(60L)
internal val ASK_ORPHAN_MARGIN: Duration = Duration.ofSeconds(60L)
internal val ASK_GIVE_UP_AFTER: Duration = Duration.ofHours(1L)

private data class AskedQuestion(
    val basicInfo: CommandBasicInfo,
    val content: MessageContent.AgentQuestion,
)

private sealed interface QuestionRow {
    data object Absent : QuestionRow

    data class Unreadable(
        val reason: String,
    ) : QuestionRow

    data class Asked(
        val question: AskedQuestion,
    ) : QuestionRow
}

private enum class ResumeStep {
    OFFERED,
    UNREADABLE,
    NOT_OFFERED,
}

@Service
class AgentAskThreadStarter(
    private val outboxRepository: MessageOutboxRepository,
    private val askTurnRepository: AgentAskTurnRepository,
    private val agentConverseService: AgentConverseService,
    private val retryService: RetryService,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    appConfig: AppConfig,
) {
    private val orphanAfter: Duration =
        Duration.ofSeconds(appConfig.agent.sidecar.requestTimeoutSeconds).plus(ASK_ORPHAN_MARGIN)
    private val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var unreadableLastSweep: Int = 0

    @EventListener
    fun onDelivered(event: MessageDeliveredEvent) {
        if (event.commandDetailType != CommandDetailType.AGENT_ASK_QUESTION) return
        val eventId = event.eventId.toString()
        if (event.messageTs.isBlank()) {
            log.warn { "Asked question eventId=$eventId was delivered without a ts; no turn can be threaded" }
            return
        }
        val asked =
            when (
                val row =
                    retryService.execute(
                        action = { questionRowOf(eventId = eventId) },
                        maxAttempts = STATUS_WRITE_ATTEMPTS,
                    )
            ) {
                QuestionRow.Absent -> {
                    log.warn { "Asked question eventId=$eventId is gone or carries no question; no turn starts" }
                    return
                }

                is QuestionRow.Unreadable -> {
                    log.error { "Asked question eventId=$eventId cannot be read (${row.reason}); no turn starts" }
                    return
                }

                is QuestionRow.Asked -> row.question
            }
        val recorded = recordedOrNull(eventId = eventId, messageTs = event.messageTs, asked = asked)
        if (recorded == null) {
            agentConverseService.submit(
                event = requestOf(messageTs = event.messageTs, asked = asked),
                lease = AgentTurnLease.Unleased,
            )
            return
        }
        val turn = recorded.turn
        if (turn.status != AgentAskTurnStatus.PENDING) return
        enqueue(turn = turn) { lease ->
            val request = requestOf(messageTs = turn.messageTs, asked = asked)
            if (recorded.storedNow) {
                agentConverseService.submit(event = request, lease = lease)
            } else {
                agentConverseService.offer(event = request, lease = lease)
            }
        }
    }

    @Scheduled(fixedDelay = ASK_RESUME_PERIOD_MILLIS, initialDelay = ASK_RESUME_PERIOD_MILLIS)
    fun resume() {
        containFailure(onFailure = { exception -> log.error(exception) { "Ask turn resume sweep failed" } }) {
            resumeOnce()
        }
    }

    fun resumeOnce(): Int {
        val now = now()
        val orphanedBefore = now.minus(orphanAfter)
        val giveUpBefore = now.minus(ASK_GIVE_UP_AFTER)
        val steps =
            askTurnRepository
                .findResumable(
                    pendingBefore = now.minus(ASK_PENDING_GRACE),
                    orphanedBefore = orphanedBefore,
                    limit =
                        ASK_RESUME_BATCH_SIZE +
                            unreadableLastSweep.coerceAtMost(maximumValue = ASK_UNREADABLE_LOOK_AHEAD_MAX),
                ).map { turn ->
                    var step = ResumeStep.NOT_OFFERED
                    containFailure(
                        onFailure = { exception ->
                            log.error(exception) { "Ask turn resume failed for eventId=${turn.eventId}" }
                        },
                    ) {
                        step =
                            resumeOne(
                                turn = turn,
                                now = now,
                                orphanedBefore = orphanedBefore,
                                giveUpBefore = giveUpBefore,
                            )
                    }
                    step
                }
        unreadableLastSweep = steps.count { it == ResumeStep.UNREADABLE }
        val resumed = steps.count { it == ResumeStep.OFFERED }
        meterRegistry.counter(AgentConverseService.METRIC_TURNS, "outcome", "resumed").increment(resumed.toDouble())
        return resumed
    }

    private fun resumeOne(
        turn: AgentAskTurnSchema,
        now: LocalDateTime,
        orphanedBefore: LocalDateTime,
        giveUpBefore: LocalDateTime,
    ): ResumeStep {
        val waiting =
            when (turn.status) {
                AgentAskTurnStatus.PENDING -> true
                AgentAskTurnStatus.IN_PROGRESS ->
                    askTurnRepository.releaseOrphan(
                        eventId = turn.eventId,
                        attemptCount = turn.attemptCount,
                        orphanedBefore = orphanedBefore,
                        now = now,
                    ) == 1
                AgentAskTurnStatus.COMPLETED, AgentAskTurnStatus.ABANDONED -> false
            }
        if (!waiting) return ResumeStep.NOT_OFFERED
        if (turn.runCount >= ASK_MAX_RUNS || turn.createdAt < giveUpBefore) {
            abandon(turn = turn)
            return ResumeStep.NOT_OFFERED
        }
        var step = ResumeStep.NOT_OFFERED
        enqueue(turn = turn) { lease ->
            step = offerOrAbandon(turn = turn, lease = lease)
            step == ResumeStep.OFFERED
        }
        return step
    }

    private fun recordedOrNull(eventId: String, messageTs: String, asked: AskedQuestion): RecordedAskTurn? =
        try {
            retryService.execute(
                action = { askTurnRepository.recordDelivered(eventId = eventId, messageTs = messageTs, now = now()) },
                maxAttempts = STATUS_WRITE_ATTEMPTS,
            )
        } catch (exception: Exception) {
            log.error(exception) {
                "Recording asked question eventId=$eventId idempotencyKey=${asked.basicInfo.idempotencyKey} failed; " +
                    "its turn starts without a ledger row"
            }
            null
        }

    private fun enqueue(turn: AgentAskTurnSchema, handOff: (AgentTurnLease) -> Boolean): Boolean {
        if (!queued.add(turn.eventId)) return false
        var accepted = false
        try {
            accepted = handOff(leaseOf(turn = turn))
        } finally {
            if (!accepted) queued.remove(turn.eventId)
        }
        return accepted
    }

    private fun abandon(turn: AgentAskTurnSchema) {
        val asked =
            when (val row = questionRowOf(eventId = turn.eventId)) {
                QuestionRow.Absent -> {
                    abandonGone(turn = turn)
                    return
                }

                is QuestionRow.Unreadable -> {
                    if (abandonWithoutNotice(turn = turn)) {
                        log.error {
                            "Ask turn eventId=${turn.eventId} abandoned without a notice after " +
                                "${turn.runCount} runs, delivered at ${turn.createdAt}: its question cannot be read " +
                                "(${row.reason})"
                        }
                        meterRegistry.counter(AgentConverseService.METRIC_TURNS, "outcome", "abandoned").increment()
                    }
                    return
                }

                is QuestionRow.Asked -> row.question
            }
        val abandoned =
            agentConverseService.abandon(
                event = requestOf(messageTs = turn.messageTs, asked = asked),
                lease = leaseOf(turn = turn),
            )
        if (!abandoned) return
        log.error {
            "Ask turn eventId=${turn.eventId} abandoned unanswered after ${turn.runCount} runs, " +
                "delivered at ${turn.createdAt}"
        }
        meterRegistry.counter(AgentConverseService.METRIC_TURNS, "outcome", "abandoned").increment()
    }

    private fun offerOrAbandon(turn: AgentAskTurnSchema, lease: AgentTurnLease): ResumeStep =
        when (val row = questionRowOf(eventId = turn.eventId)) {
            QuestionRow.Absent -> {
                abandonGone(turn = turn)
                ResumeStep.NOT_OFFERED
            }

            is QuestionRow.Unreadable -> {
                log.error {
                    "Asked question eventId=${turn.eventId} cannot be read (${row.reason}); left for a later sweep"
                }
                ResumeStep.UNREADABLE
            }

            is QuestionRow.Asked -> {
                val offered =
                    agentConverseService.offer(
                        event = requestOf(messageTs = turn.messageTs, asked = row.question),
                        lease = lease,
                    )
                if (offered) ResumeStep.OFFERED else ResumeStep.NOT_OFFERED
            }
        }

    private fun abandonGone(turn: AgentAskTurnSchema) {
        if (!abandonWithoutNotice(turn = turn)) return
        log.warn { "Asked question eventId=${turn.eventId} is gone or carries no question; abandoning its turn" }
        meterRegistry.counter(AgentConverseService.METRIC_TURNS, "outcome", "abandoned").increment()
    }

    private fun abandonWithoutNotice(turn: AgentAskTurnSchema): Boolean =
        askTurnRepository.finish(
            eventId = turn.eventId,
            attemptCount = turn.attemptCount,
            status = AgentAskTurnStatus.ABANDONED.name,
            now = now(),
        ) == 1

    private fun questionRowOf(eventId: String): QuestionRow {
        val row = outboxRepository.findByIdOrNull(id = eventId) ?: return QuestionRow.Absent
        if (row.schemaVersion !in OutboxSchemaVersion.SUPPORTED) {
            return QuestionRow.Unreadable(
                reason = "schemaVersion=${row.schemaVersion}, not in ${OutboxSchemaVersion.SUPPORTED}",
            )
        }
        val envelope =
            try {
                OutboundMessageCodec.decode(json = row.payload)
            } catch (undecodable: OutboundMessageCodecException) {
                return QuestionRow.Unreadable(reason = reasonOf(undecodable = undecodable))
            }
        val content =
            (envelope.message as? OutboundMessage.ChannelMessage)?.content as? MessageContent.AgentQuestion
                ?: return QuestionRow.Absent
        return QuestionRow.Asked(question = AskedQuestion(basicInfo = envelope.basicInfo, content = content))
    }

    private fun reasonOf(undecodable: OutboundMessageCodecException): String {
        val cause: Throwable = undecodable.cause ?: return "undecodable payload"
        val path: String = (cause as? JacksonException)?.pathReference.orEmpty()
        return if (path.isEmpty()) {
            "undecodable payload: ${cause.javaClass.simpleName}"
        } else {
            "undecodable payload: ${cause.javaClass.simpleName} at $path"
        }
    }

    private fun requestOf(messageTs: String, asked: AskedQuestion): AgentConverseRequestEvent =
        AgentConverseRequestEvent(
            idempotencyKey = asked.basicInfo.idempotencyKey,
            payload =
                AgentConversePayload(
                    prompt = asked.content.prompt,
                    threadId = messageTs,
                    requesterName = asked.content.requesterName,
                    channelName = asked.content.channelName,
                    responseBasicInfo = asked.basicInfo,
                ),
            type = CommandDetailType.AGENT_CONVERSE,
        )

    private fun leaseOf(turn: AgentAskTurnSchema): AgentTurnLease =
        AskTurnLease(eventId = turn.eventId, attempt = turn.attemptCount)

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    private inner class AskTurnLease(
        private val eventId: String,
        private var attempt: Int,
    ) : AgentTurnLease {
        override fun begin(): Boolean {
            val begun =
                try {
                    askTurnRepository.begin(eventId = eventId, attemptCount = attempt, now = now()) == 1
                } finally {
                    queued.remove(eventId)
                }
            if (begun) attempt += 1
            return begun
        }

        override fun settle() {
            finish(status = AgentAskTurnStatus.COMPLETED)
        }

        override fun giveBack(): BusyNotice {
            owned(updated = askTurnRepository.release(eventId = eventId, attemptCount = attempt, now = now()))
            return if (attempt > 1) BusyNotice.NONE else BusyNotice.ANSWER_LATER
        }

        override fun abandon() {
            finish(status = AgentAskTurnStatus.ABANDONED)
        }

        override fun discarded(): Boolean {
            queued.remove(eventId)
            return true
        }

        private fun finish(status: AgentAskTurnStatus) {
            owned(
                updated =
                    askTurnRepository.finish(
                        eventId = eventId,
                        attemptCount = attempt,
                        status = status.name,
                        now = now(),
                    ),
            )
        }

        private fun owned(updated: Int) {
            if (updated != 1) {
                throw AgentTurnLeaseLostException(
                    message = "Ask turn eventId=$eventId attempt=$attempt belongs to another run or is finished",
                )
            }
        }
    }
}
