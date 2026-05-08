package dev.notypie.repository.standup.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.standup.dto.SessionDispatchDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.domain.standup.dto.StandupSessionDto
import dev.notypie.domain.standup.entity.SessionDispatch
import dev.notypie.domain.standup.entity.StandupAnswer
import dev.notypie.domain.standup.entity.StandupSession
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * JPA mapping for [StandupSession]. The unique key `(routine_uid, session_date)` enforces
 * "channel × day = 1 session" at the DB level — re-creating a session on a restart short-
 * circuits to the existing row instead of producing duplicates.
 *
 * `summary_message_ts` is the Slack `chat.postMessage` ts of the summary post. Stored so
 * that a restarted scheduler that re-reads SUMMARIZED rows can detect "already posted" and
 * skip re-sending. Empty/NULL while [SessionStatus.COLLECTING].
 *
 * Per-member responses live in two child tables (dispatches, answers) joined by `session_id`.
 * Both are owned by the session — cascading delete keeps the schema simple to evolve.
 */
@Entity(name = "standup_session")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_standup_session_routine_date", columnNames = ["routine_uid", "session_date"]),
    ],
    indexes = [
        Index(name = "idx_standup_session_cutoff", columnList = "cutoff_at"),
    ],
)
class StandupSessionSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "session_uid", unique = true, nullable = false, length = 36)
    val sessionUid: UUID,
    @field:Column(name = "routine_uid", nullable = false, length = 36)
    val routineUid: UUID,
    @field:Column(name = "session_date", nullable = false)
    val sessionDate: LocalDate,
    @field:Column(name = "cutoff_at", nullable = false)
    val cutoffAt: Instant,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 16)
    val status: SessionStatus = SessionStatus.COLLECTING,
    @field:Column(name = "summary_message_ts", length = 64)
    val summaryMessageTs: String? = null,
    @field:OneToMany(
        mappedBy = "session",
        fetch = FetchType.LAZY,
        orphanRemoval = true,
        cascade = [CascadeType.ALL],
    )
    val dispatches: MutableList<SessionDispatchSchema> = mutableListOf(),
    @field:OneToMany(
        mappedBy = "session",
        fetch = FetchType.LAZY,
        orphanRemoval = true,
        cascade = [CascadeType.ALL],
    )
    val answers: MutableList<StandupAnswerSchema> = mutableListOf(),
    @field:CreationTimestamp
    @field:JsonProperty("created_at")
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:JsonProperty("updated_at")
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
) {
    companion object {
        const val RESPONSE_DELIMITER: String = "" // ASCII Unit Separator — never appears in Slack text.
    }
}

@Entity(name = "standup_session_dispatch")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_standup_dispatch_session_user", columnNames = ["session_id", "user_id"]),
    ],
    indexes = [
        Index(name = "idx_standup_dispatch_trigger_status", columnList = "dm_status, dm_trigger_at"),
    ],
)
class SessionDispatchSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "session_id")
    val session: StandupSessionSchema,
    @field:Column(name = "user_id", nullable = false)
    val userId: String,
    @field:Column(name = "dm_trigger_at", nullable = false)
    val dmTriggerAt: Instant,
    @field:Column(name = "dm_sent_at")
    val dmSentAt: Instant? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "dm_status", nullable = false, length = 16)
    val dmStatus: DispatchStatus = DispatchStatus.PENDING,
    @field:Column(name = "failure_reason", columnDefinition = "TEXT")
    val failureReason: String? = null,
    /**
     * Per-claim token written by [dev.notypie.repository.standup.JpaSessionDispatchRepository.claimDispatch]
     * and required by `markSent` / `markFailed` predicates. Without this token, a stuck-row
     * recovery sweep that resets a long-running claim to PENDING + a re-claim by another tick
     * could be silently clobbered when the original tick eventually called `markDispatchFailed`
     * (the row would still be SENDING but owned by tick B). The token narrows the CAS so each
     * tick only acknowledges *its own* claim's outcome.
     */
    @field:Column(name = "claim_token", length = 36)
    val claimToken: String? = null,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    /**
     * Tracks the last time `dm_status` flipped — bumped explicitly inside the atomic CAS in
     * [dev.notypie.repository.standup.JpaSessionDispatchRepository.claimDispatch] / `markSent`
     * / `markFailed` / `resetStuckSending`. The stuck-row recovery query keys off this column,
     * so a row claimed `SENDING` is only considered "stuck" once `updated_at` ages past the
     * threshold. Without the explicit bump, claims on rows with future `dm_trigger_at` would
     * be reset by the very next tick.
     */
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)

@Entity(name = "standup_answer")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_standup_answer_session_user", columnNames = ["session_id", "user_id"]),
    ],
)
class StandupAnswerSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "session_id")
    val session: StandupSessionSchema,
    @field:Column(name = "user_id", nullable = false)
    val userId: String,
    /**
     * Responses joined by [StandupSessionSchema.RESPONSE_DELIMITER] (ASCII Unit Separator).
     * That separator never appears in Slack `plain_text_input` values so the round-trip is
     * unambiguous without a child table.
     */
    @field:Column(name = "responses", nullable = false, columnDefinition = "TEXT")
    val responsesRaw: String,
    @field:Column(name = "submitted_at", nullable = false)
    val submittedAt: Instant,
)

fun StandupSession.toSchema(): StandupSessionSchema {
    val schema =
        StandupSessionSchema(
            sessionUid = sessionUid,
            routineUid = routineUid,
            sessionDate = sessionDate,
            cutoffAt = cutoffAt,
            status = status,
            summaryMessageTs = summaryMessageTs,
        )
    val dispatchRows =
        dispatchSnapshot().map { dispatch ->
            SessionDispatchSchema(
                session = schema,
                userId = dispatch.userId,
                dmTriggerAt = dispatch.dmTriggerAt,
                dmSentAt = dispatch.dmSentAt,
                dmStatus = dispatch.dmStatus,
                failureReason = dispatch.failureReason,
            )
        }
    val answerRows =
        answerSnapshot().map { answer ->
            StandupAnswerSchema(
                session = schema,
                userId = answer.userId,
                responsesRaw = answer.responses.joinToString(separator = StandupSessionSchema.RESPONSE_DELIMITER),
                submittedAt = answer.submittedAt,
            )
        }
    schema.dispatches.addAll(dispatchRows)
    schema.answers.addAll(answerRows)
    return schema
}

fun StandupSessionSchema.toDomainEntity(): StandupSession {
    val session =
        StandupSession(
            sessionUid = sessionUid,
            routineUid = routineUid,
            sessionDate = sessionDate,
            cutoffAt = cutoffAt,
            status = status,
            summaryMessageTs = summaryMessageTs,
        )
    dispatches.forEach { row ->
        session.addDispatch(
            dispatch =
                SessionDispatch(
                    userId = row.userId,
                    dmTriggerAt = row.dmTriggerAt,
                    dmSentAt = row.dmSentAt,
                    dmStatus = row.dmStatus,
                    failureReason = row.failureReason,
                ),
        )
    }
    answers.forEach { row ->
        session.addAnswer(
            answer =
                StandupAnswer(
                    userId = row.userId,
                    responses = row.responsesRaw.split(StandupSessionSchema.RESPONSE_DELIMITER),
                    submittedAt = row.submittedAt,
                ),
        )
    }
    return session
}

fun StandupSessionSchema.toStandupSessionDto(): StandupSessionDto =
    StandupSessionDto(
        sessionId = id,
        sessionUid = sessionUid,
        routineUid = routineUid,
        sessionDate = sessionDate,
        cutoffAt = cutoffAt,
        status = status,
        summaryMessageTs = summaryMessageTs,
        dispatches =
            dispatches.map { row ->
                SessionDispatchDto(
                    id = row.id,
                    userId = row.userId,
                    dmTriggerAt = row.dmTriggerAt,
                    dmSentAt = row.dmSentAt,
                    dmStatus = row.dmStatus,
                    failureReason = row.failureReason,
                )
            },
        answers =
            answers.map { row ->
                StandupAnswerDto(
                    userId = row.userId,
                    responses = row.responsesRaw.split(StandupSessionSchema.RESPONSE_DELIMITER),
                    submittedAt = row.submittedAt,
                )
            },
    )
