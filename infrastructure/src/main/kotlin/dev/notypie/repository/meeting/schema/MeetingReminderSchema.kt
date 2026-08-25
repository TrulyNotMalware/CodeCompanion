package dev.notypie.repository.meeting.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.meet.dto.MeetingReminderDto
import dev.notypie.domain.meet.entity.MeetingReminder
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime

/**
 * JPA mapping for [MeetingReminder]. The unique key `(meeting_id, offset_minutes)` enforces
 * "one reminder per meeting per offset" at the DB level — re-materializing a reminder on a
 * restart short-circuits to the existing row instead of producing duplicates, which is the
 * core idempotency guarantee the scheduler relies on.
 *
 * `claim_token` / `updated_at` mirror [SessionDispatchSchema] exactly: the per-claim token
 * narrows every atomic CAS to *this* tick's claim, and `updated_at` is bumped explicitly inside
 * each native transition so the stuck-row recovery query can age out abandoned `SENDING` rows.
 */
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
    /**
     * Per-claim token written by [dev.notypie.repository.meeting.JpaMeetingReminderRepository.claimReminder]
     * and required by `markSent` / `markFailed` predicates. Without this token, a stuck-row
     * recovery sweep that resets a long-running claim to PENDING + a re-claim by another tick
     * could be silently clobbered when the original tick eventually called `markReminderFailed`.
     */
    @field:Column(name = "claim_token", length = 36)
    val claimToken: String? = null,
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    /**
     * Tracks the last time `status` flipped — bumped explicitly inside each atomic CAS in
     * [dev.notypie.repository.meeting.JpaMeetingReminderRepository]. The stuck-row recovery
     * query keys off this column, so a row claimed `SENDING` is only considered "stuck" once
     * `updated_at` ages past the threshold.
     */
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
