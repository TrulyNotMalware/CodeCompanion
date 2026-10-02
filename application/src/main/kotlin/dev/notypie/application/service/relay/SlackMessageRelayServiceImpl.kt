package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.relayQueueCapacity
import dev.notypie.impl.command.ACCESS_BLOCKED_DEFER
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.isAccessBlocked
import dev.notypie.impl.command.isRateLimited
import dev.notypie.impl.command.isTransientExhausted
import dev.notypie.impl.command.retryAfter
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

private val DEFAULT_RATE_LIMIT_WAIT: Duration = Duration.ofSeconds(60L)
internal val RATE_LIMIT_SPREAD: Duration = Duration.ofMinutes(2L)

// Each attempt can wait a full Hikari connection-timeout on the listener thread; three ride out a deadlock or a
// dropped connection, and anything longer is an outage the recovery sweep handles (budget: relay AGENTS.md).
private const val STATUS_WRITE_ATTEMPTS = 3L

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
    appConfig: AppConfig,
) : MessageRelayService,
    SmartLifecycle {
    private val stuckThreshold: Duration = Duration.ofSeconds(appConfig.outbox.polling.stuckInProgressSeconds)
    private val giveUpAfter: Duration = Duration.ofHours(appConfig.outbox.polling.giveUpAfterHours)

    // Dispatch slots, counted from the configured queue capacity of relayTaskExecutor rather than read off the pool:
    // a reading cannot be reserved atomically (review F3), counted idle threads that cannot take a task the moment it
    // is queued (F2), and fell back to "unlimited" for any executor it could not cast (F4). A claim holds its slot from
    // the reservation until a pool thread starts it, so the queue never holds more claims than it has room for and
    // the AbortPolicy stays a safety net; a running dispatch holds none, which is the pool threads' share.
    private val slotCapacity: Int = relayQueueCapacity(appConfig = appConfig)
    private val freeSlots = AtomicInteger(slotCapacity)

    // Set by stop() at the start of the context close (review F1). From then on nothing new is sent: no slot is
    // handed out, no claim is queued, and a claim that has not reached renewClaim yet returns unsent and stays
    // IN_PROGRESS, so another pod's sweep reclaims it after the stuck threshold without a duplicate. Only dispatches
    // already past that point keep running, and relayTaskExecutor's shutdown wait covers just those.
    @Volatile
    private var stopping = false

    // Not stopping until stop(), so the bean counts as running from construction and unit tests need no start().
    override fun isRunning(): Boolean = !stopping

    override fun start() {
        stopping = false
    }

    // DEFAULT_PHASE (Integer.MAX_VALUE) stops before the Kafka listener containers (Integer.MAX_VALUE - 100), so a
    // CDC record still in hand after this point is not sent either. Left on its own, the executor (which only begins
    // its shutdown when the context destroys it) would start every queued claim during the whole lifecycle stop and
    // send it past the pod's grace period. The cast only skips the drain for an executor that is not a pool: the
    // stopping check in dispatchClaimed still keeps every queued claim from sending.
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

    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE

    override fun reserveDispatchSlots(wanted: Int): Int {
        if (stopping || wanted <= 0) return 0
        return minOf(freeSlots.getAndUpdate { free -> free - minOf(free, wanted) }, wanted)
    }

    // Capped at the capacity, so a claim handed over without a reservation cannot grow the relay past its queue.
    override fun releaseDispatchSlots(count: Int) {
        if (count <= 0) return
        freeSlots.updateAndGet { free -> minOf(free + count, slotCapacity) }
    }

    // Can't use @Async here — self-invocation from this bean would bypass the AOP proxy.
    // A rejected claim is left IN_PROGRESS without a send, so the recovery sweep reclaims it after the stuck threshold.
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
        // Every reader funnels here, so the give-up window holds even for a CDC backlog replayed after a long outage.
        if (row.createdAt < now().minus(giveUpAfter)) {
            logger.error {
                "Failing outbox row eventId=$eventId idempotencyKey=${row.idempotencyKey} unsent: created at " +
                    "${row.createdAt}, past the ${giveUpAfter.toHours()} h give-up window"
            }
            complete(
                claim = claim,
                updateEvent =
                    MessagePublishFailedEvent(
                        eventId = eventId,
                        reason = "expired: created ${row.createdAt}",
                    ),
            )
            return
        }
        // A shape only a newer release can read is left to that release: no renew (no send spent), no FAILURE.
        // The sweep reclaims it every stuck threshold until a binary that can read it sends it, or the 24 h bound ends it.
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
                messageDispatcher.dispatch(event = rendered)
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
            else -> complete(claim = claim, updateEvent = result.toOutboxUpdateEvent(eventId = eventId))
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

    // reason leads the WARN line, so an access-blocked hold does not read as a rate limit (review F6).
    private fun defer(claim: OutboxClaim, retryAfter: Duration?, reason: String) {
        val wait = (retryAfter ?: DEFAULT_RATE_LIMIT_WAIT) + spreadOf(claim = claim)
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
                    "Deferring eventId=${claim.row.eventId} after \"$reason\" failed; the sweep retries it on its own clock"
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

    private fun complete(claim: OutboxClaim, updateEvent: OutboxUpdateEvent) {
        if (!writeTerminal(claim = claim, status = updateEvent.status)) return
        try {
            applicationEventPublisher.publishEvent(updateEvent)
        } catch (exception: Exception) {
            logger.error(exception) {
                "Listener of ${updateEvent.status} failed for eventId=${updateEvent.eventId}; the row is already " +
                    "${updateEvent.status}"
            }
        }
    }

    private fun writeTerminal(claim: OutboxClaim, status: MessageStatus): Boolean {
        val completed =
            try {
                retryService.execute(
                    action = {
                        outboxRepository.completeClaim(
                            eventId = claim.row.eventId,
                            attemptCount = claim.attempt,
                            status = status.name,
                            now = now(),
                        )
                    },
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

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    // Runs inside the command's tx via BEFORE_COMMIT so the row commits atomically with it. Saved once, with no
    // RetryService (review G9, the same call as the meeting listeners in T22): a failed save has already marked the
    // shared transaction rollback-only, so an in-place retry would only sleep with the connection held and then
    // replace the original exception with a RetryException. The whole command is the unit of retry.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun saveOutboxMessage(event: OutboundMessageEnqueued) {
        val row =
            outboundMessagePort.toRow(
                message = event.payload.message,
                basicInfo = event.payload.basicInfo,
            )
        outboxRepository.save(row)
    }
}
