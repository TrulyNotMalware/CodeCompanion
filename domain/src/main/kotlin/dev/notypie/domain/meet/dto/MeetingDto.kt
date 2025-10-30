package dev.notypie.domain.meet.dto

import java.time.LocalDateTime
import java.util.UUID

data class MeetingListDto(
    val meetings: List<MeetingDto>,
)

data class MeetingDto(
    val meetingId: Long,
    val idempotencyKey: UUID,
    val creator: String,
    val title: String,
    val reason: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime? = null,
    val participantIds: List<String>,
    val isCanceled: Boolean,
)
