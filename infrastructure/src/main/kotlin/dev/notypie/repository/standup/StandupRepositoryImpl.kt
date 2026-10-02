package dev.notypie.repository.standup

import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.dto.SessionDispatchDto
import dev.notypie.domain.standup.dto.StandupSessionDto
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.standup.schema.SessionDispatchSchema
import dev.notypie.repository.standup.schema.StandupSessionSchema
import dev.notypie.repository.standup.schema.toDomainEntity
import dev.notypie.repository.standup.schema.toRoutineDto
import dev.notypie.repository.standup.schema.toSchema
import dev.notypie.repository.standup.schema.toStandupSessionDto
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

open class StandupRepositoryImpl(
    private val jpaRoutineRepository: JpaRoutineRepository,
    private val jpaStandupSessionRepository: JpaStandupSessionRepository,
    private val jpaSessionDispatchRepository: JpaSessionDispatchRepository,
) : StandupRepository {
    private fun SessionDispatchSchema.toDto() =
        SessionDispatchDto(
            id = id,
            userId = userId,
            dmTriggerAt = dmTriggerAt,
            dmSentAt = dmSentAt,
            dmStatus = dmStatus,
            failureReason = failureReason,
        )

    @Transactional
    override fun createRoutine(routine: Routine): Routine =
        jpaRoutineRepository
            .save(routine.toSchema())
            .toDomainEntity()

    @Transactional(readOnly = true)
    override fun getRoutine(routineUid: UUID): RoutineDto =
        jpaRoutineRepository
            .findByRoutineUid(routineUid = routineUid)
            ?.toRoutineDto()
            .throwIfSchemaNotFound(fieldName = "routineUid", fieldValue = routineUid)

    @Transactional(readOnly = true)
    override fun findActiveRoutinesByChannel(commandChannel: String): List<RoutineDto> =
        jpaRoutineRepository
            .findActiveByCommandChannel(channel = commandChannel)
            .map { it.toRoutineDto() }

    @Transactional(readOnly = true)
    override fun listActiveRoutines(): List<RoutineDto> =
        jpaRoutineRepository
            .findAllActive()
            .map { it.toRoutineDto() }

    @Transactional
    override fun deactivateRoutine(routineUid: UUID): Boolean =
        jpaRoutineRepository.markInactive(routineUid = routineUid) == 1

    @Transactional
    override fun createSession(session: StandupSession): StandupSession =
        jpaStandupSessionRepository
            .save(session.toSchema())
            .toDomainEntity()

    @Transactional(readOnly = true)
    override fun findSession(routineUid: UUID, sessionDate: LocalDate): StandupSessionDto? =
        jpaStandupSessionRepository
            .findByRoutineUidAndSessionDate(routineUid = routineUid, sessionDate = sessionDate)
            ?.toStandupSessionDto()

    @Transactional(readOnly = true)
    override fun findSession(sessionUid: UUID): StandupSessionDto? =
        jpaStandupSessionRepository
            .findBySessionUid(sessionUid = sessionUid)
            ?.toStandupSessionDto()

    // The caller's transaction must hold the row lock while it saves the summary and flips the status.
    @Transactional(propagation = Propagation.MANDATORY)
    override fun findSessionForSummary(sessionUid: UUID): StandupSessionDto? =
        jpaStandupSessionRepository.findLockedBySessionUid(sessionUid = sessionUid)?.let { session ->
            session.toStandupSessionDto().copy(status = lockedStatusOf(session = session))
        }

    @Transactional
    override fun recordAnswer(
        sessionUid: UUID,
        userId: String,
        responses: List<String>,
        submittedAt: Instant,
    ): AnswerRecordResult {
        val session =
            jpaStandupSessionRepository.findLockedBySessionUid(sessionUid = sessionUid)
                ?: return AnswerRecordResult.SESSION_NOT_FOUND
        if (lockedStatusOf(session = session) != SessionStatus.COLLECTING || !submittedAt.isBefore(session.cutoffAt)) {
            return AnswerRecordResult.SESSION_CLOSED
        }
        jpaStandupSessionRepository.upsertAnswer(
            sessionId = session.id,
            userId = userId,
            responses = responses.joinToString(separator = StandupSessionSchema.RESPONSE_DELIMITER),
            submittedAt = submittedAt,
        )
        return AnswerRecordResult.RECORDED
    }

    // An instance already in the persistence context keeps its stale status after the locking query; read it again.
    private fun lockedStatusOf(session: StandupSessionSchema): SessionStatus =
        SessionStatus.valueOf(jpaStandupSessionRepository.findLockedStatus(id = session.id))

    override fun claimDispatch(dispatchId: Long, claimToken: String, now: Instant): Boolean =
        jpaSessionDispatchRepository.claimDispatch(id = dispatchId, token = claimToken, now = now) == 1

    @Transactional
    override fun markDispatchSent(dispatchId: Long, claimToken: String, sentAt: Instant): Boolean =
        jpaSessionDispatchRepository.markSent(id = dispatchId, token = claimToken, sentAt = sentAt) == 1

    @Transactional
    override fun markDispatchFailed(
        dispatchId: Long,
        claimToken: String,
        reason: String,
        now: Instant,
    ): Boolean =
        jpaSessionDispatchRepository.markFailed(id = dispatchId, token = claimToken, reason = reason, now = now) == 1

    override fun resetStuckDispatches(olderThan: Instant, now: Instant): Int =
        jpaSessionDispatchRepository.resetStuckSending(olderThan = olderThan, now = now)

    @Transactional(readOnly = true)
    override fun findPendingDispatchesBefore(before: Instant, limit: Int): List<ReadyDispatch> =
        jpaSessionDispatchRepository
            .findPendingBefore(before = before, pageable = PageRequest.of(0, limit))
            .map { schema ->
                ReadyDispatch(
                    dispatch = schema.toDto(),
                    sessionUid = schema.session.sessionUid,
                    sessionDate = schema.session.sessionDate,
                    cutoffAt = schema.session.cutoffAt,
                    sessionStatus = schema.session.status,
                    summaryMessageTs = schema.session.summaryMessageTs,
                    routineUid = schema.session.routineUid,
                )
            }

    @Transactional(readOnly = true)
    override fun findCollectingSessionsPastCutoff(before: Instant): List<StandupSessionDto> =
        jpaStandupSessionRepository
            .findCollectingPastCutoff(before = before)
            .map { it.toStandupSessionDto() }

    override fun markSessionSummarized(sessionId: Long, messageTs: String): Boolean =
        jpaStandupSessionRepository.markSummarized(id = sessionId, messageTs = messageTs) == 1

    @Transactional(readOnly = true)
    override fun findCollectingSessionsForNudge(now: Instant, nudgeWindowEnd: Instant): List<NudgeCandidateSession> =
        jpaStandupSessionRepository
            .findCollectingForNudge(now = now, nudgeWindowEnd = nudgeWindowEnd)
            .map { schema ->
                NudgeCandidateSession(
                    sessionId = schema.id,
                    sessionUid = schema.sessionUid,
                    routineUid = schema.routineUid,
                    cutoffAt = schema.cutoffAt,
                    sentMemberIds =
                        schema.dispatches
                            .filter { it.dmStatus == DispatchStatus.SENT }
                            .map { it.userId }
                            .toSet(),
                    answeredUserIds = schema.answers.map { it.userId }.toSet(),
                )
            }

    override fun claimNudge(sessionId: Long): Boolean = jpaStandupSessionRepository.claimNudge(id = sessionId) == 1

    override fun replaceSummaryMessageTs(currentMessageTs: String, messageTs: String): Boolean =
        jpaStandupSessionRepository.replaceSummaryMessageTs(
            currentMessageTs = currentMessageTs,
            messageTs = messageTs,
        ) == 1
}
