package dev.notypie.domain.meet.dto

import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

internal data class MeetingListDto(
    val meetings: List<MeetingDto>,
)

data class MeetingDto(
    val meetingId: Long,
    val meetingUid: UUID,
    val idempotencyKey: UUID,
    val creator: String,
    val title: String,
    val reason: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime? = null,
    val participants: List<MeetingParticipantDto>,
    val isCanceled: Boolean,
)

data class MeetingParticipantDto(
    val userId: String,
    val isAttending: Boolean,
    val absentReason: RejectReason = RejectReason.ATTENDING,
    val absentReasonDetail: String? = null,
)

data class MeetingReminderDto(
    val id: Long,
    val meetingId: Long,
    val offsetMinutes: Int,
    val scheduledAt: Instant,
    val sentAt: Instant?,
    val status: MeetingReminderStatus,
    val failureReason: String?,
)
