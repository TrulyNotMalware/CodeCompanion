package dev.notypie.repository.meeting.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.command.dto.interactions.RejectReason
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity(name = "meetings")
class MeetingSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,

    @field:Column(name = "idempotency_key", unique = true, nullable = false)
    val idempotencyKey: UUID,

    @field:Column(name = "start_at", nullable = false)
    val startAt: LocalDateTime,

    @field:Column(name = "end_at")
    val endAt: LocalDateTime? = null,

    @field:Column(name = "is_canceled", nullable = false)
    val isCanceled: Boolean = false,

    @field:OneToMany(mappedBy = "meeting", fetch = FetchType.LAZY,
        orphanRemoval = true,
        cascade = [CascadeType.MERGE, CascadeType.PERSIST])
    val participants: MutableList<ParticipantsSchema> = mutableListOf(),

    @field:Column(name = "publisher_id", nullable = false)
    val publisherId: String,

    @field:Column(name = "channel", nullable = false)
    val channel: String,

    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    )


@Entity(name = "meeting_participants")
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
    val isAttending: Boolean = false,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "absent_reason")
    val absentReason: RejectReason = RejectReason.ATTENDING,

    @field:CreationTimestamp
    @field:Column(name ="created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null
)