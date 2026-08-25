package dev.notypie.domain.standup.dto

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Read-side projection of a [dev.notypie.domain.standup.entity.Routine]. Returned by the
 * repository; consumed by application services that don't need the entity's mutability.
 */
data class RoutineDto(
    val routineId: Long,
    val routineUid: UUID,
    val name: String,
    val creatorId: String,
    val commandChannel: String,
    val summaryChannel: String,
    val questions: List<String>,
    val triggerLocalTime: LocalTime,
    val cutoffOffset: Duration,
    val weekdays: Set<DayOfWeek>,
    val routineTimezone: ZoneId,
    val isActive: Boolean,
    val members: List<RoutineMemberDto>,
)

data class RoutineMemberDto(
    val userId: String,
    val userTimezone: ZoneId,
)
