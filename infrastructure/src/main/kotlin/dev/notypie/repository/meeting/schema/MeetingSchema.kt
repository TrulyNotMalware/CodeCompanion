package dev.notypie.repository.meeting.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.dto.MeetingParticipantDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.domain.meet.entity.RejectReason
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity(name = "meetings")
@Table(
    indexes = [
        Index(name = "idx_meetings_canceled_start_at", columnList = "is_canceled, start_at"),
        Index(name = "idx_meetings_publisher_start_at", columnList = "publisher_id, start_at"),
    ],
)
class MeetingSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "meeting_uid", unique = true, nullable = false, length = 36)
    val meetingUid: UUID,
    @field:Column(name = "idempotency_key", unique = true, nullable = false)
    val idempotencyKey: UUID,
    @field:Column(name = "name", nullable = false)
    val name: String,
    @field:Column(name = "start_at", nullable = false)
    val startAt: LocalDateTime,
    @field:Column(name = "end_at")
    val endAt: LocalDateTime? = null,
    @field:Column(name = "is_canceled", nullable = false)
    val isCanceled: Boolean = false,
    @field:OneToMany(
        mappedBy = "meeting",
        fetch = FetchType.LAZY,
        orphanRemoval = false,
        cascade = [CascadeType.MERGE, CascadeType.PERSIST],
    )
    val participants: MutableList<ParticipantsSchema> = mutableListOf(),
    @field:Column(name = "publisher_id", nullable = false)
    val publisherId: String,
    @field:Column(name = "channel", nullable = false)
    val channel: String,
    @field:Column(name = "reason")
    val reason: String? = null,
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
) {
    // Bumped with OPTIMISTIC_FORCE_INCREMENT on participant writes, since adding to the mappedBy
    // collection alone leaves the parent row untouched and two concurrent adds would both pass the cap.
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0L
        protected set
}

fun Meeting.toSchema(idempotencyKey: UUID, channel: String): MeetingSchema {
    val meetingSchema =
        MeetingSchema(
            meetingUid = meetingUid,
            idempotencyKey = idempotencyKey,
            startAt = startAt,
            endAt = endAt,
            isCanceled = isCanceled,
            publisherId = host.userId,
            channel = channel,
            name = title,
            reason = reason,
        )

    val participants =
        memberIdSnapshot().map {
            ParticipantsSchema(
                meeting = meetingSchema,
                userId = it,
            )
        }
    meetingSchema.participants.addAll(participants)
    return meetingSchema
}

fun MeetingSchema.toDomainEntity() =
    Meeting(
        startAt = startAt,
        endAt = endAt ?: startAt.plusHours(1),
        isCanceled = isCanceled,
        publisher = publisherId,
        title = name,
        members = participants.map { it.userId }.toSet(),
        reason = reason ?: "",
        meetingUid = meetingUid,
    )

fun MeetingSchema.toMeetingDto() =
    MeetingDto(
        meetingId = id,
        meetingUid = meetingUid,
        idempotencyKey = idempotencyKey,
        startAt = startAt,
        endAt = endAt,
        isCanceled = isCanceled,
        title = name,
        creator = publisherId,
        reason = "",
        participants =
            participants.map { p ->
                MeetingParticipantDto(
                    userId = p.userId,
                    isAttending = p.isAttending,
                    absentReason = p.absentReason,
                    absentReasonDetail = p.absentReasonDetail,
                )
            },
    )

@Entity(name = "meeting_participants")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_meeting_participants_meeting_user", columnNames = ["meeting_id", "user_id"]),
    ],
    indexes = [
        Index(name = "idx_meeting_participants_user_id", columnList = "user_id"),
    ],
)
class ParticipantsSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "meeting_id")
    val meeting: MeetingSchema,
    @field:Column(name = "user_id", nullable = false)
    val userId: String,
    @field:Column(name = "is_attending", nullable = false)
    val isAttending: Boolean = true,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "absent_reason")
    val absentReason: RejectReason = RejectReason.ATTENDING,
    @field:Column(name = "absent_reason_detail")
    val absentReasonDetail: String? = null,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)
