package dev.notypie.repository.standup

import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.dto.SessionDispatchDto
import dev.notypie.domain.standup.dto.StandupSessionDto
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.standup.schema.SessionDispatchSchema
import dev.notypie.repository.standup.schema.StandupAnswerSchema
import dev.notypie.repository.standup.schema.StandupSessionSchema
import dev.notypie.repository.standup.schema.toDomainEntity
import dev.notypie.repository.standup.schema.toRoutineDto
import dev.notypie.repository.standup.schema.toSchema
import dev.notypie.repository.standup.schema.toStandupSessionDto
import jakarta.transaction.Transactional
import org.springframework.data.domain.PageRequest
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

    override fun getRoutine(routineUid: UUID): RoutineDto =
        jpaRoutineRepository
            .findByRoutineUid(routineUid = routineUid)
            ?.toRoutineDto()
            .throwIfSchemaNotFound(fieldName = "routineUid", fieldValue = routineUid)

    override fun findActiveRoutinesByChannel(commandChannel: String): List<RoutineDto> =
        jpaRoutineRepository
            .findActiveByCommandChannel(channel = commandChannel)
            .map { it.toRoutineDto() }

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

    override fun findSession(routineUid: UUID, sessionDate: LocalDate): StandupSessionDto? =
        jpaStandupSessionRepository
            .findByRoutineUidAndSessionDate(routineUid = routineUid, sessionDate = sessionDate)
            ?.toStandupSessionDto()

    override fun findSession(sessionUid: UUID): StandupSessionDto? =
        jpaStandupSessionRepository
            .findBySessionUid(sessionUid = sessionUid)
            ?.toStandupSessionDto()

    @Transactional
    override fun recordAnswer(
        sessionUid: UUID,
        userId: String,
        responses: List<String>,
        submittedAt: Instant,
    ): Boolean {
        val session =
            jpaStandupSessionRepository.findBySessionUid(sessionUid = sessionUid)
                ?: return false
        session.answers.removeIf { it.userId == userId }
        session.answers.add(
            StandupAnswerSchema(
                session = session,
                userId = userId,
                responsesRaw = responses.joinToString(separator = StandupSessionSchema.RESPONSE_DELIMITER),
                submittedAt = submittedAt,
            ),
        )
        jpaStandupSessionRepository.save(session)
        return true
    }

    override fun claimDispatch(dispatchId: Long, claimToken: String): Boolean =
        jpaSessionDispatchRepository.claimDispatch(id = dispatchId, token = claimToken) == 1

    @Transactional
    override fun markDispatchSent(dispatchId: Long, claimToken: String, sentAt: Instant): Boolean =
        jpaSessionDispatchRepository.markSent(id = dispatchId, token = claimToken, sentAt = sentAt) == 1

    @Transactional
    override fun markDispatchFailed(dispatchId: Long, claimToken: String, reason: String): Boolean =
        jpaSessionDispatchRepository.markFailed(id = dispatchId, token = claimToken, reason = reason) == 1

    override fun resetStuckDispatches(olderThan: Instant): Int =
        jpaSessionDispatchRepository.resetStuckSending(olderThan = olderThan)

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

    override fun findCollectingSessionsPastCutoff(before: Instant): List<StandupSessionDto> =
        jpaStandupSessionRepository
            .findCollectingPastCutoff(before = before)
            .map { it.toStandupSessionDto() }

    override fun markSessionSummarized(sessionId: Long, messageTs: String): Boolean =
        jpaStandupSessionRepository.markSummarized(id = sessionId, messageTs = messageTs) == 1

    override fun replaceSummaryMessageTs(currentMessageTs: String, messageTs: String): Boolean =
        jpaStandupSessionRepository.replaceSummaryMessageTs(
            currentMessageTs = currentMessageTs,
            messageTs = messageTs,
        ) == 1
}
