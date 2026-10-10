package dev.notypie.repository.calendar.schema

import dev.notypie.repository.meeting.schema.MeetingSchema
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime

@Entity(name = "meeting_calendar_event")
@Table(
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_meeting_calendar_event_meeting_user",
            columnNames = ["meeting_id", "slack_user_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_meeting_calendar_event_status_next_attempt", columnList = "status, next_attempt_at"),
        Index(name = "idx_meeting_calendar_event_user", columnList = "slack_user_id"),
    ],
)
class MeetingCalendarEventSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(
        name = "meeting_id",
        nullable = false,
        foreignKey = ForeignKey(name = "fk_meeting_calendar_event_meeting"),
    )
    @field:OnDelete(action = OnDeleteAction.CASCADE)
    val meeting: MeetingSchema,
    @field:Column(name = "slack_user_id", nullable = false, length = 64)
    val slackUserId: String,
    @field:Column(name = "google_event_id", length = 1024)
    val googleEventId: String? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 16)
    val status: CalendarSyncStatus = CalendarSyncStatus.PENDING,
    @field:Column(name = "change_seq", nullable = false)
    val changeSeq: Long = 1L,
    @field:Column(name = "attempts", nullable = false)
    val attempts: Int = 0,
    @field:Column(name = "next_attempt_at", nullable = false)
    val nextAttemptAt: Instant,
    @field:Column(name = "claim_token", length = 36)
    val claimToken: String? = null,
    @field:Column(name = "last_error", columnDefinition = "TEXT")
    val lastError: String? = null,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)

fun MeetingCalendarEventSchema.toMeetingCalendarEvent(): MeetingCalendarEvent =
    MeetingCalendarEvent(
        id = id,
        meetingId = meeting.id,
        slackUserId = slackUserId,
        googleEventId = googleEventId,
        status = status,
        changeSeq = changeSeq,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
    )

data class MeetingCalendarEvent(
    val id: Long,
    val meetingId: Long,
    val slackUserId: String,
    val googleEventId: String?,
    val status: CalendarSyncStatus,
    val changeSeq: Long,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
)
