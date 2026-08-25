package dev.notypie.domain.standup.dto

import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Read-side projection of a [dev.notypie.domain.standup.entity.StandupSession] including all
 * dispatches and answers. Used by the channel-summary renderer so a single round-trip pulls
 * everything needed to format the post.
 */
data class StandupSessionDto(
    val sessionId: Long,
    val sessionUid: UUID,
    val routineUid: UUID,
    val sessionDate: LocalDate,
    val cutoffAt: Instant,
    val status: SessionStatus,
    val summaryMessageTs: String?,
    val dispatches: List<SessionDispatchDto>,
    val answers: List<StandupAnswerDto>,
)

data class SessionDispatchDto(
    val id: Long,
    val userId: String,
    val dmTriggerAt: Instant,
    val dmSentAt: Instant?,
    val dmStatus: DispatchStatus,
    val failureReason: String?,
)

data class StandupAnswerDto(
    val userId: String,
    val responses: List<String>,
    val submittedAt: Instant,
)
