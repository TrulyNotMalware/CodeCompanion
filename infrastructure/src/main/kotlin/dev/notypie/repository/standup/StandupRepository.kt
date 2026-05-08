package dev.notypie.repository.standup

import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.dto.SessionDispatchDto
import dev.notypie.domain.standup.dto.StandupSessionDto
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.domain.standup.entity.enums.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Read view returned by [StandupRepository.findPendingDispatchesBefore]. Carries enough
 * session/routine context that the scheduler can build a DM without an extra round-trip
 * per dispatch — and crucially, it lets the scheduler dispatch members whose local trigger
 * time fell on a *different* calendar day than the session's routine-zone date.
 */
data class ReadyDispatch(
    val dispatch: SessionDispatchDto,
    val sessionUid: UUID,
    val sessionDate: LocalDate,
    val cutoffAt: Instant,
    val sessionStatus: SessionStatus,
    val summaryMessageTs: String?,
    val routineUid: UUID,
)

/**
 * Domain-facing repository for the standup-bot feature. The interface is intentionally
 * narrow at the #10 stage — only enough to round-trip routines and sessions. Scheduling,
 * dispatch, and answer submission queries land in #12 / #13 once the contracts are clearer.
 */
interface StandupRepository {
    fun createRoutine(routine: Routine): Routine

    fun getRoutine(routineUid: UUID): RoutineDto

    fun findActiveRoutinesByChannel(commandChannel: String): List<RoutineDto>

    fun listActiveRoutines(): List<RoutineDto>

    fun deactivateRoutine(routineUid: UUID): Boolean

    /**
     * Persists a brand-new standup session. The unique constraint
     * `(routine_uid, session_date)` guarantees at most one row per routine per day; this
     * method does *not* upsert — callers must check via [findSession] first if they need to
     * decide between "create" and "update". Concrete update methods (mark-summarized,
     * record-answer, etc.) ship in Phase 3 #12 / #13 once the scheduler/collector contracts
     * are firm.
     */
    fun createSession(session: StandupSession): StandupSession

    fun findSession(routineUid: UUID, sessionDate: LocalDate): StandupSessionDto?

    fun findSession(sessionUid: UUID): StandupSessionDto?

    fun recordAnswer(
        sessionUid: UUID,
        userId: String,
        responses: List<String>,
        submittedAt: Instant,
    ): Boolean

    /**
     * Atomically transitions PENDING→SENDING and stamps [claimToken] on the row. Callers must
     * generate a fresh token per claim attempt and pass the same token to [markDispatchSent]
     * or [markDispatchFailed] so the audit transition is keyed to *this* claim — without the
     * token, a stuck-row recovery + concurrent re-claim would silently let one tick clobber
     * another tick's outcome.
     */
    fun claimDispatch(dispatchId: Long, claimToken: String): Boolean

    /**
     * Atomically transitions SENDING→SENT, but only when [claimToken] matches the row's
     * current token. Returns true iff exactly one row changed; false means recovery or
     * another tick has already moved the row out of *our* claim and the outbox-side write
     * should be rolled back.
     */
    fun markDispatchSent(dispatchId: Long, claimToken: String, sentAt: Instant): Boolean

    /**
     * Atomically transitions SENDING→FAILED, but only when [claimToken] matches. Returns
     * true iff exactly one row changed; false means our claim was already invalidated.
     */
    fun markDispatchFailed(dispatchId: Long, claimToken: String, reason: String): Boolean

    /** Resets SENDING dispatches older than threshold back to PENDING (crash recovery). */
    fun resetStuckDispatches(olderThan: Instant): Int

    /**
     * Finds PENDING dispatches ready to send (dmTriggerAt <= before), eagerly joined with
     * their owning session so the scheduler can build a DM and route the click without
     * having to re-derive the session date from the routine's timezone — which is wrong for
     * members in zones where their local 10:00 falls on a different calendar day.
     */
    fun findPendingDispatchesBefore(before: Instant, limit: Int): List<ReadyDispatch>

    /** Finds COLLECTING sessions past their cutoff — used by the cutoff detector. */
    fun findCollectingSessionsPastCutoff(before: Instant): List<StandupSessionDto>

    /** Atomic COLLECTING→SUMMARIZED; returns true if the transition succeeded. */
    fun markSessionSummarized(sessionId: Long, messageTs: String): Boolean

    fun replaceSummaryMessageTs(currentMessageTs: String, messageTs: String): Boolean
}
