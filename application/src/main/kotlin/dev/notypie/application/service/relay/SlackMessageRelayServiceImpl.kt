package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.relayQueueCapacity
import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.SLACK_DISPATCH_TIME_BOUND
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.isAccessBlocked
import dev.notypie.impl.command.isOutcomeUnknown
import dev.notypie.impl.command.isRateLimited
import dev.notypie.impl.command.isTransientExhausted
import dev.notypie.impl.command.retryAfter
import dev.notypie.impl.retry.RetryService
import dev.notypie.impl.retry.retryTimeBound
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundEnvelope
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.chainedParts
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

private val DEFAULT_RATE_LIMIT_WAIT: Duration = Duration.ofSeconds(60L)
internal val RATE_LIMIT_SPREAD: Duration = Duration.ofMinutes(2L)
internal val ACCESS_BLOCKED_DEFER: Duration = Duration.ofMinutes(15L)

// Each attempt can wait a full Hikari connection-timeout; the per-record budget in relay AGENTS.md counts three.
private const val STATUS_WRITE_ATTEMPTS = 3L

// One dispatch with a healthy pool: render's profile lookup, the Slack calls, the status write's retries.
val RELAY_RECORD_TIME_BOUND: Duration =
    RestClientRequester.DEFAULT_READ_TIMEOUT
        .plus(SLACK_DISPATCH_TIME_BOUND)
        .plus(retryTimeBound(attemptTimeout = Duration.ZERO, maxAttempts = STATUS_WRITE_ATTEMPTS))

// A held row is invisible to every outbox count (defer takes its send back and moves updated_at), so health reads this.
@Component
class AccessBlockedTracker {
    private val lastBlockedAt = AtomicReference<Instant?>(null)

    fun record(at: Instant) {
        lastBlockedAt.updateAndGet { previous -> if (previous == null || at > previous) at else previous }
    }

    fun lastBlockedAt(): Instant? = lastBlockedAt.get()
}

@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    private val payloadRenderer: OutboxPayloadRenderer,
    private val messageDispatcher: MessageDispatcher,
    private val retryService: RetryService,
    private val applicationEventPublisher: ApplicationEventPublisher,
    @Qualifier("relayTaskExecutor") private val relayTaskExecutor: Executor,
    private val clock: Clock,
    private val accessBlockedTracker: AccessBlockedTracker,
    transactionManager: PlatformTransactionManager,
    appConfig: AppConfig,
) : MessageRelayService,
    SmartLifecycle {
    private val statusTransaction = TransactionTemplate(transactionManager)
    private val stuckThreshold: Duration = Duration.ofSeconds(appConfig.outbox.polling.stuckInProgressSeconds)
    private val giveUpAfter: Duration = Duration.ofHours(appConfig.outbox.polling.giveUpAfterHours)
    private val slotCapacity: Int = relayQueueCapacity(appConfig = appConfig)
    private val freeSlots = AtomicInteger(slotCapacity)

    @Volatile
    private var stopping = false

    override fun isRunning(): Boolean = !stopping

    override fun start() {
        stopping = false
    }

    // Without the drain the pool keeps starting queued claims until it is destroyed, sending them past the grace period.
    override fun stop() {
        stopping = true
        val dropped = ArrayList<Runnable>()
        (relayTaskExecutor as? ThreadPoolTaskExecutor)?.threadPoolExecutor?.queue?.drainTo(dropped)
        releaseDispatchSlots(count = dropped.size)
        logger.info {
            "Relay stopping: ${dropped.size} queued claims left IN_PROGRESS unsent for the recovery sweep; " +
                "only dispatches already running are waited for"
        }
    }

    override fun reserveDispatchSlots(wanted: Int): Int {
        if (stopping || wanted <= 0) return 0
        return minOf(freeSlots.getAndUpdate { free -> free - minOf(free, wanted) }, wanted)
    }

    override fun releaseDispatchSlots(count: Int) {
        if (count <= 0) return
        freeSlots.updateAndGet { free -> minOf(free + count, slotCapacity) }
    }

    // Can't use @Async here — self-invocation from this bean would bypass the AOP proxy.
    override fun batchPendingMessages(claims: List<OutboxClaim>) {
        if (stopping) {
            releaseDispatchSlots(count = claims.size)
            logger.info {
                "Relay stopping; leaving eventIds=${claims.map { it.row.eventId }} IN_PROGRESS for the recovery sweep"
            }
            return
        }
        claims.forEachIndexed { index, claim ->
            try {
                relayTaskExecutor.execute {
                    releaseDispatchSlots(count = 1)
                    dispatchClaimed(claim = claim)
                }
            } catch (rejected: RejectedExecutionException) {
                val left = claims.drop(index)
                releaseDispatchSlots(count = left.size)
                logger.warn(rejected) {
                    "Relay executor rejected ${left.size} of ${claims.size} claims; leaving eventIds=" +
                        "${left.map { it.row.eventId }} IN_PROGRESS for the recovery sweep"
                }
                return
            }
        }
    }

    // Keyed on the row's eventId, not the renderer's payload eventId, which is throwaway.
    override fun dispatchClaimed(claim: OutboxClaim) {
        val row = claim.row
        if (stopping) {
            logger.info { "Relay stopping; leaving eventId=${row.eventId} IN_PROGRESS unsent for the recovery sweep" }
            return
        }
        val eventId =
            runCatching { UUID.fromString(row.eventId) }
                .getOrElse { parseFailure ->
                    logger.error(parseFailure) {
                        "Failing outbox row with malformed eventId='${row.eventId}' idempotencyKey=${row.idempotencyKey}"
                    }
                    writeTerminal(claim = claim, status = MessageStatus.FAILURE)
                    return
                }
        // Every reader funnels here, so the give-up bound also holds for a CDC backlog replayed after an outage.
        if (row.createdAt < now().minus(giveUpAfter)) {
            logger.error {
                "Failing outbox row eventId=$eventId idempotencyKey=${row.idempotencyKey} unsent: created at " +
                    "${row.createdAt}, past the ${giveUpAfter.toHours()} h give-up window"
            }
            complete(
                claim = claim,
                updateEvent =
                    MessagePublishFailedEvent(eventId = eventId, reason = "expired: created ${row.createdAt}"),
            )
            return
        }
        // A newer release's row waits, unsent and unfailed, for a binary that reads it or the give-up bound.
        if (row.schemaVersion !in OutboxSchemaVersion.SUPPORTED) {
            logger.error {
                "Outbox row eventId=$eventId idempotencyKey=${row.idempotencyKey} has schemaVersion=" +
                    "${row.schemaVersion}, not in ${OutboxSchemaVersion.SUPPORTED}; leaving it IN_PROGRESS unsent"
            }
            return
        }
        if (!renew(claim = claim)) return

        val rendered =
            try {
                payloadRenderer.render(row = row)
            } catch (exception: Exception) {
                logger.error(exception) { "Render failed for eventId=$eventId idempotencyKey=${row.idempotencyKey}" }
                complete(
                    claim = claim,
                    updateEvent = MessagePublishFailedEvent(eventId = eventId, reason = exception.toString()),
                )
                return
            }
        val result =
            try {
                messageDispatcher.dispatch(event = rendered.payload)
            } catch (exception: Exception) {
                logger.error(exception) {
                    "Dispatch threw for eventId=$eventId idempotencyKey=${row.idempotencyKey}; " +
                        "leaving it IN_PROGRESS for the recovery sweep"
                }
                return
            }
        when {
            result.isRateLimited() ->
                defer(
                    claim = claim,
                    retryAfter = result.retryAfter(),
                    reason = "Slack rate limit (Retry-After=${result.retryAfter()?.toSeconds()}s)",
                )
            result.isAccessBlocked() -> {
                logger.error { "Slack refused the bot's access; holding eventId=$eventId until it is fixed" }
                accessBlockedTracker.record(at = clock.instant())
                defer(claim = claim, retryAfter = ACCESS_BLOCKED_DEFER, reason = "Slack access blocked")
            }
            result.isTransientExhausted() ->
                logger.warn { "Slack transient failure; leaving eventId=$eventId IN_PROGRESS for the recovery sweep" }
            else ->
                complete(
                    claim = claim,
                    updateEvent = result.toOutboxUpdateEvent(eventId = eventId),
                    next = rendered.next.takeIf { result.ok || result.isOutcomeUnknown() },
                )
        }
    }

    private fun renew(claim: OutboxClaim): Boolean {
        val renewed =
            runCatching {
                outboxRepository.renewClaim(eventId = claim.row.eventId, attemptCount = claim.attempt, now = now())
            }.getOrElse { exception ->
                logger.error(exception) {
                    "Claim renewal failed for eventId=${claim.row.eventId}; leaving it to the recovery sweep"
                }
                return false
            }
        if (renewed != 1) {
            logger.warn {
                "Claim attempt=${claim.attempt} on eventId=${claim.row.eventId} was taken over; not dispatching"
            }
        }
        return renewed == 1
    }

    private fun defer(claim: OutboxClaim, retryAfter: Duration?, reason: String) {
        val wait = minOf(retryAfter ?: DEFAULT_RATE_LIMIT_WAIT, giveUpAfter) + spreadOf(claim = claim)
        val eligibleAt = minOf(now().plus(wait), claim.row.createdAt.plus(giveUpAfter))
        val deferred =
            runCatching {
                outboxRepository.deferClaim(
                    eventId = claim.row.eventId,
                    attemptCount = claim.attempt,
                    updatedAt = eligibleAt.minus(stuckThreshold),
                )
            }.getOrElse { exception ->
                logger.error(exception) {
                    "Deferring eventId=${claim.row.eventId} after \"$reason\" failed; " +
                        "the sweep retries it on its own clock"
                }
                return
            }
        logger.warn {
            "$reason; eventId=${claim.row.eventId} " +
                (if (deferred == 1) "deferred until $eligibleAt" else "was taken over while deferring")
        }
    }

    private fun spreadOf(claim: OutboxClaim): Duration {
        val hash = claim.row.eventId.hashCode()
        return Duration.ofMillis(Math.floorMod(hash.toLong(), RATE_LIMIT_SPREAD.toMillis()))
    }

    private fun complete(claim: OutboxClaim, updateEvent: OutboxUpdateEvent, next: OutboundEnvelope? = null) {
        if (!writeTerminal(claim = claim, status = updateEvent.status, next = next)) return
        if (next == null && updateEvent.status == MessageStatus.FAILURE) logDroppedChain(row = claim.row)
        try {
            applicationEventPublisher.publishEvent(updateEvent)
        } catch (exception: Exception) {
            logger.error(exception) {
                "Listener of ${updateEvent.status} failed for eventId=${updateEvent.eventId}; the row is already " +
                    "${updateEvent.status}"
            }
        }
    }

    private fun writeTerminal(claim: OutboxClaim, status: MessageStatus, next: OutboundEnvelope? = null): Boolean {
        val completed =
            try {
                retryService.execute(
                    action = { completeAndChain(claim = claim, status = status, next = next) },
                    maxAttempts = STATUS_WRITE_ATTEMPTS,
                )
            } catch (exception: Exception) {
                logger.error(exception) {
                    "Recording $status failed for eventId=${claim.row.eventId} " +
                        "idempotencyKey=${claim.row.idempotencyKey}; row stays IN_PROGRESS for the recovery sweep"
                }
                return false
            }
        if (completed != 1) {
            logger.warn {
                "Claim attempt=${claim.attempt} on eventId=${claim.row.eventId} was taken over; $status not recorded"
            }
        }
        return completed == 1
    }

    private fun completeAndChain(claim: OutboxClaim, status: MessageStatus, next: OutboundEnvelope?): Int =
        checkNotNull(
            statusTransaction.execute {
                val completed =
                    outboxRepository.completeClaim(
                        eventId = claim.row.eventId,
                        attemptCount = claim.attempt,
                        status = status.name,
                        now = now(),
                    )
                // Only in the transaction of the CAS that won, so a retried or taken-over write never stages it twice.
                if (completed == 1 && next != null) outboxRepository.save(nextRow(row = claim.row, next = next))
                completed
            },
        )

    private fun nextRow(row: OutboxMessage, next: OutboundEnvelope): OutboxMessage =
        outboundMessagePort.toRow(
            message = next.message,
            basicInfo = next.basicInfo,
            transport = Transport.valueOf(row.transport),
            continuation = next.continuation,
        )

    private fun logDroppedChain(row: OutboxMessage) {
        val dropped = row.chainedParts()
        if (dropped > 0) {
            logger.error {
                "Dropping $dropped chained parts after eventId=${row.eventId} idempotencyKey=${row.idempotencyKey} " +
                    "ended FAILURE"
            }
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    // Runs inside the command's tx via BEFORE_COMMIT so the row commits atomically with it.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun saveOutboxMessage(event: OutboundMessageEnqueued) {
        val row =
            outboundMessagePort.toRow(
                message = event.payload.message,
                basicInfo = event.payload.basicInfo,
                continuation = event.payload.continuation,
            )
        outboxRepository.save(row)
    }
}
