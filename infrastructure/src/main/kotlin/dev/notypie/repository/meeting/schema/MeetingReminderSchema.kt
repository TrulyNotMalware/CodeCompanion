package dev.notypie.repository.meeting.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.meet.dto.MeetingReminderDto
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime

@Entity(name = "meeting_reminder")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_meeting_reminder_meeting_offset", columnNames = ["meeting_id", "offset_minutes"]),
    ],
    indexes = [
        Index(name = "idx_meeting_reminder_scheduled_status", columnList = "status, scheduled_at"),
    ],
)
class MeetingReminderSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "meeting_id")
    val meeting: MeetingSchema,
    @field:Column(name = "offset_minutes", nullable = false)
    val offsetMinutes: Int,
    @field:Column(name = "scheduled_at", nullable = false)
    val scheduledAt: Instant,
    @field:Column(name = "sent_at")
    val sentAt: Instant? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 16)
    val status: MeetingReminderStatus = MeetingReminderStatus.PENDING,
    @field:Column(name = "failure_reason", columnDefinition = "TEXT")
    val failureReason: String? = null,
    @field:Column(name = "claim_token", length = 36)
    val claimToken: String? = null,
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:JsonProperty("updated_at")
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)

fun MeetingReminderSchema.toMeetingReminderDto(): MeetingReminderDto =
    MeetingReminderDto(
        id = id,
        meetingId = meeting.id,
        offsetMinutes = offsetMinutes,
        scheduledAt = scheduledAt,
        sentAt = sentAt,
        status = status,
        failureReason = failureReason,
    )
