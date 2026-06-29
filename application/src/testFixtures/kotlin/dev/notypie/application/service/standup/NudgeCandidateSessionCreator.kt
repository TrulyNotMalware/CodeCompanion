package dev.notypie.application.service.standup

import dev.notypie.repository.standup.NudgeCandidateSession
import java.time.Instant
import java.util.UUID

fun createNudgeCandidateSession(
    sessionId: Long = 1L,
    sessionUid: UUID = UUID.randomUUID(),
    routineUid: UUID = UUID.randomUUID(),
    cutoffAt: Instant = Instant.parse("2026-05-01T02:00:00Z"),
    sentMemberIds: Set<String> = emptySet(),
    answeredUserIds: Set<String> = emptySet(),
): NudgeCandidateSession =
    NudgeCandidateSession(
        sessionId = sessionId,
        sessionUid = sessionUid,
        routineUid = routineUid,
        cutoffAt = cutoffAt,
        sentMemberIds = sentMemberIds,
        answeredUserIds = answeredUserIds,
    )
