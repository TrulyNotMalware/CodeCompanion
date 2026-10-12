package dev.notypie.domain.standup.dto

import java.util.UUID

data class StandupAnswerCandidate(
    val sessionUid: UUID,
    val routineUid: UUID,
    val routineName: String,
)
