package dev.notypie.application.service.standup

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.StandupCutoffEvent
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.entity.SessionDispatch
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
import dev.notypie.repository.standup.ReadyDispatch
import dev.notypie.repository.standup.StandupRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class StandupSchedulingService(
    private val standupRepository: StandupRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val messageBuilder: StandupDispatchMessageBuilder,
    transactionManager: PlatformTransactionManager,
    private val applicationEventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { },
    private val clock: Clock = Clock.systemDefaultZone(),
    @param:Value("\${standup.scheduler.stuck-sending-threshold-minutes:5}")
    private val stuckSendingThresholdMinutes: Long = 5L,
    @param:Value("\${standup.scheduler.dispatch-batch-size:50}")
    private val dispatchBatchSize: Int = 50,
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Phase 1: Creates today's standup session + per-member dispatch rows for every active
     * routine that fires on today's weekday. The unique constraint (routine_uid, session_date)
     * makes this fully idempotent — a restart or duplicate tick simply finds the existing row.
     */
    fun openSessionsForToday() {
        val now = clock.instant()
        standupRepository.listActiveRoutines().forEach { routine ->
            openSessionForRoutine(routine = routine, now = now)
        }
    }

    private fun openSessionForRoutine(routine: RoutineDto, now: Instant) {
        val today = LocalDate.ofInstant(now, routine.routineTimezone)
        if (today.dayOfWeek !in routine.weekdays) return
        if (standupRepository.findSession(routineUid = routine.routineUid, sessionDate = today) != null) return

        // Per-member DM trigger time, evaluated in each member's own zone — an LA member of a
        // Seoul routine receives the prompt at LA 10:00, not Seoul 10:00.
        val memberDispatches =
            routine.members.map { member ->
                val dmTriggerAt =
                    LocalDateTime
                        .of(today, routine.triggerLocalTime)
                        .atZone(member.userTimezone)
                        .toInstant()
                SessionDispatch(userId = member.userId, dmTriggerAt = dmTriggerAt)
            }

        // Cutoff anchors on the LATEST member trigger so every member has at least
        // [Routine.cutoffOffset] to respond. Using a routine-zone-only cutoff would mean
        // members in westward zones receive the DM after the cutoff has already passed.
        val routineFallbackTrigger =
            LocalDateTime.of(today, routine.triggerLocalTime).atZone(routine.routineTimezone).toInstant()
        val cutoffAnchor =
            memberDispatches.maxOfOrNull { it.dmTriggerAt } ?: routineFallbackTrigger
        val cutoffAt = cutoffAnchor.plus(routine.cutoffOffset)

        val session =
            StandupSession(
                routineUid = routine.routineUid,
                sessionDate = today,
                cutoffAt = cutoffAt,
            )
        memberDispatches.forEach(session::addDispatch)

        try {
            standupRepository.createSession(session = session)
            log.info {
                "Standup session opened: routine=${routine.routineUid} date=$today members=${routine.members.size}"
            }
        } catch (ex: DataIntegrityViolationException) {
            // The unique constraint (routine_uid, session_date) was the *expected* failure
            // mode (concurrent racing tick). We confirm the row actually exists before
            // swallowing — any other constraint violation (UUID collision, child-row schema
            // bug, missing column) must surface so it doesn't masquerade as a benign race.
            val existing =
                standupRepository.findSession(routineUid = routine.routineUid, sessionDate = today)
            if (existing != null) {
                log.debug {
                    "Session race lost (concurrent tick): routine=${routine.routineUid} date=$today"
                }
            } else {
                throw ex
            }
        }
    }

    /**
     * Phase 2: Sends standup DM prompts for members whose [SessionDispatch.dmTriggerAt] has
     * passed. Each dispatch is claimed atomically (PENDING→SENDING) before sending so a
     * concurrent scheduler tick cannot double-send. Stuck SENDING rows from a prior crash
     * are reset to PENDING at the start of each tick.
     *
     * Crucially, this method queries pending dispatches by absolute UTC time across *all*
     * sessions — it does NOT scope to "today's session in the routine timezone." Members
     * whose local trigger time falls on a different calendar day than the routine creator
     * (e.g. an LA member of a Seoul routine) would otherwise be stranded in PENDING.
     */
    fun sendPendingDispatches() {
        val now = clock.instant()
        val stuckCutoff = now.minus(Duration.ofMinutes(stuckSendingThresholdMinutes))
        val reset = standupRepository.resetStuckDispatches(olderThan = stuckCutoff)
        if (reset > 0) log.warn { "Reset $reset stuck SENDING dispatch(es) to PENDING" }

        val ready = standupRepository.findPendingDispatchesBefore(before = now, limit = dispatchBatchSize)
        if (ready.isEmpty()) return

        // One-shot lookup: minimise DB chatter when many dispatches share a routine.
        val routinesByUid = standupRepository.listActiveRoutines().associateBy { it.routineUid }

        ready.forEach { item ->
            val routine = routinesByUid[item.routineUid]
            if (routine == null) {
                log.warn {
                    "Pending dispatch points at unknown/inactive routine: " +
                        "dispatchId=${item.dispatch.id} routineUid=${item.routineUid}"
                }
                return@forEach
            }
            processDispatch(item = item, routine = routine, sentAt = now)
        }
    }

    /**
     * Sends one dispatch with two transaction boundaries so partial failures cannot leave
     * the system in inconsistent state:
     *
     *   1. **Claim** runs in its own transaction (the JPA repo's `@Modifying @Transactional`).
     *      A successful claim leaves the row in `SENDING` durably so the failure path below
     *      can transition it to `FAILED` even if step 2 rolls back.
     *
     *   2. **Send** wraps `(build → outbox.save → markDispatchSent)` in one
     *      [TransactionTemplate]. If `markDispatchSent` returns false (recovery already moved
     *      the row out of `SENDING`) we mark the tx for rollback — that undoes the outbox
     *      save so we don't deliver a DM the dispatch state can't account for.
     *
     * If step 2 fails or rolls back, we then call `markDispatchFailed` in a *new* transaction
     * to record the audit. Its CAS predicate (`status = 'SENDING'`) makes this safe: if
     * recovery already reset the row to PENDING, the call is a no-op and the next tick
     * re-claims naturally.
     */
    private fun processDispatch(item: ReadyDispatch, routine: RoutineDto, sentAt: Instant) {
        val dispatchId = item.dispatch.id
        val userId = item.dispatch.userId
        // Per-claim token tied to this exact processDispatch invocation. The CAS predicate
        // on every transition checks the token, so a stuck-row recovery + re-claim by another
        // tick cannot have its outcome silently overwritten by our markDispatchFailed call.
        val claimToken = UUID.randomUUID().toString()

        if (!standupRepository.claimDispatch(dispatchId = dispatchId, claimToken = claimToken)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                val commandBasicInfo =
                    CommandBasicInfo.forOutbound(
                        publisherId = userId,
                        // Slack accepts a user_id as the DM channel target.
                        channel = userId,
                    )
                val dmEvent =
                    messageBuilder.buildDmNotice(
                        sessionUid = item.sessionUid,
                        sessionDate = item.sessionDate,
                        routineUid = routine.routineUid,
                        routineName = routine.name,
                        memberId = userId,
                        commandBasicInfo = commandBasicInfo,
                    )
                outboxRepository.save(dmEvent.toOutboxMessage())
                if (!standupRepository.markDispatchSent(
                        dispatchId = dispatchId,
                        claimToken = claimToken,
                        sentAt = sentAt,
                    )
                ) {
                    // Crash-recovery sweep flipped this row out of SENDING (or another tick
                    // re-claimed it). Rolling back undoes the outbox save above so we don't
                    // deliver a DM whose dispatch row no longer represents our claim.
                    error(
                        "markDispatchSent had no effect — dispatch $dispatchId is no longer " +
                            "SENDING under our claim token. Tx will be rolled back.",
                    )
                }
            }

        if (outcome.isFailure) {
            val ex = outcome.exceptionOrNull()!!
            log.error(ex) { "Standup DM dispatch failed: dispatchId=$dispatchId userId=$userId" }
            // Fresh transaction. Token-keyed CAS guarantees we only mark FAILED for OUR claim;
            // if recovery + re-claim already happened, this is a no-op and we log it.
            if (!standupRepository.markDispatchFailed(
                    dispatchId = dispatchId,
                    claimToken = claimToken,
                    reason = ex.message ?: "unknown",
                )
            ) {
                log.warn {
                    "markDispatchFailed had no effect — dispatch $dispatchId no longer holds our " +
                        "claim token. Recovery sweep already reset or another tick re-claimed."
                }
            }
        } else {
            log.info { "Standup DM enqueued: dispatchId=$dispatchId userId=$userId" }
        }
    }

    /** Phase 3: Detects COLLECTING sessions that have passed their cutoff and queues summaries. */
    fun detectCutoffs() {
        val now = clock.instant()
        standupRepository.findCollectingSessionsPastCutoff(before = now).forEach { session ->
            log.info { "Standup cutoff reached: sessionUid=${session.sessionUid} routineUid=${session.routineUid}" }
            applicationEventPublisher.publishEvent(
                StandupCutoffEvent(
                    sessionId = session.sessionId,
                    sessionUid = session.sessionUid,
                    routineUid = session.routineUid,
                    sessionDate = session.sessionDate,
                ),
            )
        }
    }
}
