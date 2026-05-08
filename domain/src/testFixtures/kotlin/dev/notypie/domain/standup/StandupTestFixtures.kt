package dev.notypie.domain.standup

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.RoutineMember
import dev.notypie.domain.standup.entity.SessionDispatch
import dev.notypie.domain.standup.entity.StandupAnswer
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private val DEFAULT_QUESTIONS: List<String> =
    listOf(
        "What did you do yesterday?",
        "What are you doing today?",
        "Any blockers?",
    )

fun createRoutine(
    name: String = "Daily Standup",
    creatorId: String = TEST_USER_ID,
    commandChannel: String = TEST_CHANNEL_ID,
    summaryChannel: String = TEST_CHANNEL_ID,
    questions: List<String> = DEFAULT_QUESTIONS,
    triggerLocalTime: LocalTime = LocalTime.of(10, 0),
    cutoffOffset: Duration = Routine.DEFAULT_CUTOFF_OFFSET,
    weekdays: Set<DayOfWeek> = Routine.DEFAULT_WEEKDAYS,
    routineTimezone: ZoneId = ZoneId.of("Asia/Seoul"),
    isActive: Boolean = true,
    routineUid: UUID = UUID.randomUUID(),
    members: List<RoutineMember> = emptyList(),
): Routine =
    Routine(
        name = name,
        creatorId = creatorId,
        commandChannel = commandChannel,
        summaryChannel = summaryChannel,
        questions = questions,
        triggerLocalTime = triggerLocalTime,
        cutoffOffset = cutoffOffset,
        weekdays = weekdays,
        routineTimezone = routineTimezone,
        isActive = isActive,
        routineUid = routineUid,
    ).apply { members.forEach(::addMember) }

fun createRoutineMember(userId: String = TEST_USER_ID, userTimezone: ZoneId = ZoneId.of("Asia/Seoul")): RoutineMember =
    RoutineMember(userId = userId, userTimezone = userTimezone)

fun createStandupSession(
    routineUid: UUID = UUID.randomUUID(),
    sessionDate: LocalDate = LocalDate.of(2026, 5, 1),
    cutoffAt: Instant = Instant.parse("2026-05-01T02:00:00Z"),
    status: SessionStatus = SessionStatus.COLLECTING,
    summaryMessageTs: String? = null,
    sessionUid: UUID = UUID.randomUUID(),
    dispatches: List<SessionDispatch> = emptyList(),
    answers: List<StandupAnswer> = emptyList(),
): StandupSession =
    StandupSession(
        routineUid = routineUid,
        sessionDate = sessionDate,
        cutoffAt = cutoffAt,
        status = status,
        summaryMessageTs = summaryMessageTs,
        sessionUid = sessionUid,
    ).apply {
        dispatches.forEach(::addDispatch)
        answers.forEach(::addAnswer)
    }

fun createSessionDispatch(
    userId: String = TEST_USER_ID,
    dmTriggerAt: Instant = Instant.parse("2026-05-01T01:00:00Z"),
    dmSentAt: Instant? = null,
    dmStatus: DispatchStatus = DispatchStatus.PENDING,
    failureReason: String? = null,
): SessionDispatch =
    SessionDispatch(
        userId = userId,
        dmTriggerAt = dmTriggerAt,
        dmSentAt = dmSentAt,
        dmStatus = dmStatus,
        failureReason = failureReason,
    )

fun createStandupAnswer(
    userId: String = TEST_USER_ID,
    responses: List<String> = listOf("did x", "doing y", "no blockers"),
    submittedAt: Instant = Instant.parse("2026-05-01T01:30:00Z"),
): StandupAnswer =
    StandupAnswer(
        userId = userId,
        responses = responses,
        submittedAt = submittedAt,
    )

fun createRoutineDto(
    routineId: Long = 1L,
    routineUid: UUID = UUID.randomUUID(),
    name: String = "Daily Standup",
    creatorId: String = TEST_USER_ID,
    commandChannel: String = TEST_CHANNEL_ID,
    summaryChannel: String = TEST_CHANNEL_ID,
    questions: List<String> = DEFAULT_QUESTIONS,
    triggerLocalTime: LocalTime = LocalTime.of(10, 0),
    cutoffOffset: Duration = Duration.ofHours(1L),
    weekdays: Set<DayOfWeek> =
        setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        ),
    routineTimezone: ZoneId = ZoneId.of("Asia/Seoul"),
    isActive: Boolean = true,
    members: List<dev.notypie.domain.standup.dto.RoutineMemberDto> = emptyList(),
): dev.notypie.domain.standup.dto.RoutineDto =
    dev.notypie.domain.standup.dto.RoutineDto(
        routineId = routineId,
        routineUid = routineUid,
        name = name,
        creatorId = creatorId,
        commandChannel = commandChannel,
        summaryChannel = summaryChannel,
        questions = questions,
        triggerLocalTime = triggerLocalTime,
        cutoffOffset = cutoffOffset,
        weekdays = weekdays,
        routineTimezone = routineTimezone,
        isActive = isActive,
        members = members,
    )

fun createRoutineMemberDto(
    userId: String = TEST_USER_ID,
    userTimezone: ZoneId = ZoneId.of("Asia/Seoul"),
): dev.notypie.domain.standup.dto.RoutineMemberDto =
    dev.notypie.domain.standup.dto
        .RoutineMemberDto(userId = userId, userTimezone = userTimezone)

fun createStandupSessionDto(
    sessionId: Long = 1L,
    sessionUid: UUID = UUID.randomUUID(),
    routineUid: UUID = UUID.randomUUID(),
    sessionDate: java.time.LocalDate = java.time.LocalDate.of(2026, 5, 1),
    cutoffAt: Instant = Instant.parse("2026-05-01T02:00:00Z"),
    status: dev.notypie.domain.standup.entity.enums.SessionStatus =
        dev.notypie.domain.standup.entity.enums.SessionStatus.COLLECTING,
    summaryMessageTs: String? = null,
    dispatches: List<dev.notypie.domain.standup.dto.SessionDispatchDto> = emptyList(),
    answers: List<dev.notypie.domain.standup.dto.StandupAnswerDto> = emptyList(),
): dev.notypie.domain.standup.dto.StandupSessionDto =
    dev.notypie.domain.standup.dto.StandupSessionDto(
        sessionId = sessionId,
        sessionUid = sessionUid,
        routineUid = routineUid,
        sessionDate = sessionDate,
        cutoffAt = cutoffAt,
        status = status,
        summaryMessageTs = summaryMessageTs,
        dispatches = dispatches,
        answers = answers,
    )

fun createSessionDispatchDto(
    id: Long = 1L,
    userId: String = TEST_USER_ID,
    dmTriggerAt: Instant = Instant.parse("2026-05-01T01:00:00Z"),
    dmSentAt: Instant? = null,
    dmStatus: dev.notypie.domain.standup.entity.enums.DispatchStatus =
        dev.notypie.domain.standup.entity.enums.DispatchStatus.PENDING,
    failureReason: String? = null,
): dev.notypie.domain.standup.dto.SessionDispatchDto =
    dev.notypie.domain.standup.dto.SessionDispatchDto(
        id = id,
        userId = userId,
        dmTriggerAt = dmTriggerAt,
        dmSentAt = dmSentAt,
        dmStatus = dmStatus,
        failureReason = failureReason,
    )
