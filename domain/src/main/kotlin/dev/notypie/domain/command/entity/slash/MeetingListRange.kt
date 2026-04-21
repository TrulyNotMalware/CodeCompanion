package dev.notypie.domain.command.entity.slash

import java.time.LocalDateTime

/**
 * Date range filter for the `/meetup list` slash command.
 *
 * Each variant defines a half-open window `[start, end)` calculated relative to a reference
 * `LocalDateTime`. TODAY/TOMORROW snap to the day boundary; WEEK/MONTH are rolling windows
 * anchored at `now` (not calendar boundaries).
 */
enum class MeetingListRange(
    val token: String,
) {
    TODAY(token = "today") {
        override fun dateRange(now: LocalDateTime): Pair<LocalDateTime, LocalDateTime> {
            val startOfToday = now.toLocalDate().atStartOfDay()
            return startOfToday to startOfToday.plusDays(1L)
        }
    },
    TOMORROW(token = "tomorrow") {
        override fun dateRange(now: LocalDateTime): Pair<LocalDateTime, LocalDateTime> {
            val startOfTomorrow = now.toLocalDate().plusDays(1L).atStartOfDay()
            return startOfTomorrow to startOfTomorrow.plusDays(1L)
        }
    },
    WEEK(token = "week") {
        override fun dateRange(now: LocalDateTime): Pair<LocalDateTime, LocalDateTime> = now to now.plusDays(7L)
    },
    MONTH(token = "month") {
        override fun dateRange(now: LocalDateTime): Pair<LocalDateTime, LocalDateTime> = now to now.plusDays(30L)
    }, ;

    abstract fun dateRange(now: LocalDateTime): Pair<LocalDateTime, LocalDateTime>

    companion object {
        val DEFAULT: MeetingListRange = WEEK

        /**
         * Case-insensitive, whitespace-tolerant token match. Returns null for unknown or blank input.
         */
        fun parseOrNull(token: String): MeetingListRange? {
            val normalized = token.trim().lowercase()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.token == normalized }
        }

        fun usageTokens(): String = entries.joinToString(separator = " | ") { it.token }
    }
}
