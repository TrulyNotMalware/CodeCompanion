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

data class ReadyDispatch(
    val dispatch: SessionDispatchDto,
    val sessionUid: UUID,
    val sessionDate: LocalDate,
    val cutoffAt: Instant,
    val sessionStatus: SessionStatus,
    val summaryMessageTs: String?,
    val routineUid: UUID,
)

data class NudgeCandidateSession(
    val sessionId: Long,
    val sessionUid: UUID,
    val routineUid: UUID,
    val cutoffAt: Instant,
    val sentMemberIds: Set<String>,
    val answeredUserIds: Set<String>,
)

interface StandupRepository {
    fun createRoutine(routine: Routine): Routine

    fun getRoutine(routineUid: UUID): RoutineDto

    fun findActiveRoutinesByChannel(commandChannel: String): List<RoutineDto>

    fun listActiveRoutines(): List<RoutineDto>

    fun deactivateRoutine(routineUid: UUID): Boolean

    fun createSession(session: StandupSession): StandupSession

    fun findSession(routineUid: UUID, sessionDate: LocalDate): StandupSessionDto?

    fun findSession(sessionUid: UUID): StandupSessionDto?

    fun recordAnswer(
        sessionUid: UUID,
        userId: String,
        responses: List<String>,
        submittedAt: Instant,
    ): Boolean

    fun claimDispatch(dispatchId: Long, claimToken: String): Boolean

    fun markDispatchSent(dispatchId: Long, claimToken: String, sentAt: Instant): Boolean

    fun markDispatchFailed(dispatchId: Long, claimToken: String, reason: String): Boolean

    fun resetStuckDispatches(olderThan: Instant): Int

    fun findPendingDispatchesBefore(before: Instant, limit: Int): List<ReadyDispatch>

    fun findCollectingSessionsPastCutoff(before: Instant): List<StandupSessionDto>

    fun markSessionSummarized(sessionId: Long, messageTs: String): Boolean

    fun findCollectingSessionsForNudge(now: Instant, nudgeWindowEnd: Instant): List<NudgeCandidateSession>

    fun claimNudge(sessionId: Long): Boolean

    fun replaceSummaryMessageTs(currentMessageTs: String, messageTs: String): Boolean
}
