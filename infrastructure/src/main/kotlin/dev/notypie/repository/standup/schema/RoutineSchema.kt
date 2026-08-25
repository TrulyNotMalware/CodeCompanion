package dev.notypie.repository.standup.schema

import com.fasterxml.jackson.annotation.JsonProperty
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.entity.Routine
import dev.notypie.domain.standup.entity.RoutineMember
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * JPA mapping for [Routine]. The standup config table.
 *
 * Persistence choices worth a comment:
 *  - **Questions:** stored as a TEXT column joined by `\n`. The domain layer rejects newline
 *    characters inside a question so the round-trip cannot ambiguate.
 *  - **Weekdays:** stored as a comma-joined `name()` list (e.g. "MONDAY,TUESDAY,..."). Native
 *    enum sets in JPA require either an extra join table or DB-specific bitmask types; the
 *    string form is the cheapest and reads fine in DB tools.
 *  - **Timezone:** the IANA id (`ZoneId.id`) is stored verbatim. `LocalTime` is stored via
 *    the JDBC TIME type which Hibernate handles natively.
 *  - **`cutoff_offset_seconds`:** stored as an integer to keep the column DB-portable.
 */
@Entity(name = "standup_routine")
class RoutineSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "routine_uid", unique = true, nullable = false, length = 36)
    val routineUid: UUID,
    @field:Column(name = "name", nullable = false, length = 60)
    val name: String,
    @field:Column(name = "creator_id", nullable = false)
    val creatorId: String,
    @field:Column(name = "command_channel", nullable = false)
    val commandChannel: String,
    @field:Column(name = "summary_channel", nullable = false)
    val summaryChannel: String,
    @field:Column(name = "questions", nullable = false, columnDefinition = "TEXT")
    val questionsRaw: String,
    @field:Column(name = "trigger_local_time", nullable = false)
    val triggerLocalTime: LocalTime,
    @field:Column(name = "cutoff_offset_seconds", nullable = false)
    val cutoffOffsetSeconds: Long,
    @field:Column(name = "weekdays", nullable = false, length = 80)
    val weekdaysRaw: String,
    @field:Column(name = "routine_timezone", nullable = false, length = 64)
    val routineTimezone: String,
    @field:Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
    @field:OneToMany(
        mappedBy = "routine",
        fetch = FetchType.LAZY,
        orphanRemoval = true,
        cascade = [CascadeType.ALL],
    )
    val members: MutableList<RoutineMemberSchema> = mutableListOf(),
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
        const val QUESTION_DELIMITER: String = "\n"
        const val WEEKDAY_DELIMITER: String = ","
    }
}

@Entity(name = "standup_routine_member")
class RoutineMemberSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "routine_id")
    val routine: RoutineSchema,
    @field:Column(name = "user_id", nullable = false)
    val userId: String,
    @field:Column(name = "user_timezone", nullable = false, length = 64)
    val userTimezone: String,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

/**
 * Builds a fully-populated [RoutineSchema] (with member rows) from a domain [Routine].
 * Members in the routine are converted into owned `RoutineMemberSchema` rows back-pointing
 * to the parent — the cascade on the owning side persists them in the same `save()` call.
 */
fun Routine.toSchema(): RoutineSchema {
    val schema =
        RoutineSchema(
            routineUid = routineUid,
            name = name,
            creatorId = creatorId,
            commandChannel = commandChannel,
            summaryChannel = summaryChannel,
            questionsRaw = questions.joinToString(separator = RoutineSchema.QUESTION_DELIMITER),
            triggerLocalTime = triggerLocalTime,
            cutoffOffsetSeconds = cutoffOffset.seconds,
            weekdaysRaw = weekdays.joinToString(separator = RoutineSchema.WEEKDAY_DELIMITER) { it.name },
            routineTimezone = routineTimezone.id,
            isActive = isActive,
        )
    val memberRows =
        memberSnapshot().map { member ->
            RoutineMemberSchema(
                routine = schema,
                userId = member.userId,
                userTimezone = member.userTimezone.id,
            )
        }
    schema.members.addAll(memberRows)
    return schema
}

fun RoutineSchema.toDomainEntity(): Routine {
    val routine =
        Routine(
            name = name,
            creatorId = creatorId,
            commandChannel = commandChannel,
            summaryChannel = summaryChannel,
            questions = questionsRaw.split(RoutineSchema.QUESTION_DELIMITER),
            triggerLocalTime = triggerLocalTime,
            cutoffOffset = Duration.ofSeconds(cutoffOffsetSeconds),
            weekdays =
                weekdaysRaw
                    .split(RoutineSchema.WEEKDAY_DELIMITER)
                    .filter { it.isNotBlank() }
                    .map { DayOfWeek.valueOf(it) }
                    .toSet(),
            routineTimezone = ZoneId.of(routineTimezone),
            isActive = isActive,
            routineUid = routineUid,
        )
    members.forEach { row ->
        routine.addMember(
            member =
                RoutineMember(
                    userId = row.userId,
                    userTimezone = ZoneId.of(row.userTimezone),
                ),
        )
    }
    return routine
}

fun RoutineSchema.toRoutineDto(): RoutineDto =
    RoutineDto(
        routineId = id,
        routineUid = routineUid,
        name = name,
        creatorId = creatorId,
        commandChannel = commandChannel,
        summaryChannel = summaryChannel,
        questions = questionsRaw.split(RoutineSchema.QUESTION_DELIMITER),
        triggerLocalTime = triggerLocalTime,
        cutoffOffset = Duration.ofSeconds(cutoffOffsetSeconds),
        weekdays =
            weekdaysRaw
                .split(RoutineSchema.WEEKDAY_DELIMITER)
                .filter { it.isNotBlank() }
                .map { DayOfWeek.valueOf(it) }
                .toSet(),
        routineTimezone = ZoneId.of(routineTimezone),
        isActive = isActive,
        members =
            members.map { row ->
                RoutineMemberDto(userId = row.userId, userTimezone = ZoneId.of(row.userTimezone))
            },
    )
