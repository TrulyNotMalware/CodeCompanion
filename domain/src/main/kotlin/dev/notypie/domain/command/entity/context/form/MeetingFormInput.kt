package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.meet.entity.Meeting
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Snapshot of the meeting-request modal's submitted state, already decoded into domain values.
 *
 * Owning the Slack `view_submission` parsing here keeps [RequestMeetingContext] focused on command
 * execution (validate → build → notice) rather than on how the Slack payload is shaped. The Meeting
 * entity still owns its own invariants — [toMeeting] simply hands the parsed values to its constructor.
 */
internal data class MeetingFormInput(
    val publisher: String,
    val participants: Set<String>,
    val startAt: LocalDateTime?,
    val endAt: LocalDateTime?,
    val title: String,
    val reason: String,
    val noticeRequired: Boolean,
) {
    /**
     * Precondition: [startAt] was validated to be a non-null future moment, and [endAt] (if present)
     * is strictly after it. The Meeting entity re-validates these invariants and may throw.
     */
    fun toMeeting(): Meeting {
        val start =
            requireNotNull(startAt) { "Meeting startAt must be validated before building Meeting entity" }
        return if (endAt != null) {
            Meeting(
                publisher = publisher,
                title = title,
                reason = reason,
                startAt = start,
                endAt = endAt,
                members = participants,
            )
        } else {
            Meeting(
                publisher = publisher,
                title = title,
                reason = reason,
                startAt = start,
                members = participants,
            )
        }
    }

    companion object {
        internal const val DATE_PATTERN = "yyyy-MM-dd"
        internal const val SIMPLE_TIME_PATTERN = "HH:mm"
        internal const val DEFAULT_MEETING_TITLE = "New Meeting"
        internal const val DEFAULT_MEETING_REASON = "request meeting"

        fun from(payload: InteractionPayload): MeetingFormInput {
            val publisher = payload.user.id
            val startAt = parseStartDateTime(payload = payload)
            val (title, reason) = parseTitleAndReason(payload = payload)
            return MeetingFormInput(
                publisher = publisher,
                participants = parseParticipants(states = payload.states, publisher = publisher),
                startAt = startAt,
                endAt = parseEndDateTime(payload = payload, startAt = startAt),
                title = title,
                reason = reason,
                noticeRequired = parseNoticeRequired(payload = payload),
            )
        }

        private fun parseParticipants(states: List<States>, publisher: String): Set<String> =
            states
                .firstOrNull { state -> state.type == ActionElementTypes.MULTI_USERS_SELECT }
                ?.takeIf { state -> state.selectedValue.isNotEmpty() }
                ?.selectedValue
                ?.split(",")
                ?.filter { participant -> participant != publisher }
                ?.toSet()
                ?: emptySet()

        /**
         * Reads the first of the modal's two TIME_PICKERs (start, end) combined with the DATE_PICKER.
         * Returns null if date/start-time is missing or the combined moment is not strictly in the future.
         */
        private fun parseStartDateTime(payload: InteractionPayload): LocalDateTime? {
            val timeString =
                payload.states
                    .filter { it.type == ActionElementTypes.TIME_PICKER && it.selectedValue.isNotBlank() }
                    .getOrNull(index = 0)
                    ?.selectedValue
            val dateString =
                payload.states
                    .firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
                    ?.selectedValue
            return if (timeString != null && dateString != null && isFutureTime(dateString, timeString)) {
                parseLocalDateTime(dateString = dateString, timeString = timeString)
            } else {
                null
            }
        }

        /**
         * Reads the second TIME_PICKER (end) against the same DATE_PICKER. Returns null when the
         * end-time picker is empty, when [startAt] is null, or when the date picker is missing.
         */
        private fun parseEndDateTime(payload: InteractionPayload, startAt: LocalDateTime?): LocalDateTime? {
            if (startAt == null) return null
            val endTimeString =
                payload.states
                    .filter { it.type == ActionElementTypes.TIME_PICKER && it.selectedValue.isNotBlank() }
                    .getOrNull(index = 1)
                    ?.selectedValue
                    ?: return null
            val dateString =
                payload.states
                    .firstOrNull { it.type == ActionElementTypes.DATE_PICKER }
                    ?.selectedValue
                    ?: return null
            return parseLocalDateTime(dateString = dateString, timeString = endTimeString)
        }

        private fun parseTitleAndReason(payload: InteractionPayload): Pair<String, String> =
            payload.states
                .filter { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
                .takeIf { it.size >= 2 }
                ?.let {
                    val title = it[0].selectedValue.ifBlank { DEFAULT_MEETING_TITLE }
                    val reason = it[1].selectedValue.ifBlank { DEFAULT_MEETING_REASON }
                    title to reason
                } ?: (DEFAULT_MEETING_TITLE to DEFAULT_MEETING_REASON)

        private fun parseNoticeRequired(payload: InteractionPayload): Boolean =
            payload.states
                .firstOrNull { it.type == ActionElementTypes.CHECKBOX }
                ?.isSelected ?: false

        private fun parseLocalDateTime(dateString: String, timeString: String): LocalDateTime =
            LocalDateTime.parse(
                "$dateString $timeString",
                DateTimeFormatter.ofPattern("$DATE_PATTERN $SIMPLE_TIME_PATTERN"),
            )

        private fun isFutureTime(dateString: String, timeString: String): Boolean {
            val date = LocalDate.parse(dateString, DateTimeFormatter.ofPattern(DATE_PATTERN))
            val time = LocalTime.parse(timeString, DateTimeFormatter.ofPattern(SIMPLE_TIME_PATTERN))
            return LocalDateTime.of(date, time).isAfter(LocalDateTime.now())
        }
    }
}
