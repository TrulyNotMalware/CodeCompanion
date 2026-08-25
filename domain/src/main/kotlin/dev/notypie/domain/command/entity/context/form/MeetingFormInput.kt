package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundForm
import dev.notypie.domain.command.inbound.InboundInteraction
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

        fun from(interaction: InboundInteraction): MeetingFormInput {
            val publisher = interaction.actor.id
            val form = interaction.form
            val startAt = parseStartDateTime(form = form)
            val (title, reason) = parseTitleAndReason(form = form)
            return MeetingFormInput(
                publisher = publisher,
                participants = parseParticipants(form = form, publisher = publisher),
                startAt = startAt,
                endAt = parseEndDateTime(form = form, startAt = startAt),
                title = title,
                reason = reason,
                noticeRequired = parseNoticeRequired(form = form),
            )
        }

        private fun parseParticipants(form: InboundForm, publisher: String): Set<String> =
            form
                .first(kind = InboundFieldKind.USERS)
                ?.takeIf { field -> field.rawValue.isNotEmpty() }
                ?.rawValue
                ?.split(",")
                ?.filter { participant -> participant != publisher }
                ?.toSet()
                ?: emptySet()

        /**
         * Reads the first of the modal's two TIME pickers (start, end) combined with the DATE picker.
         * Returns null if date/start-time is missing or the combined moment is not strictly in the future.
         */
        private fun parseStartDateTime(form: InboundForm): LocalDateTime? {
            val timeString =
                form
                    .all(kind = InboundFieldKind.TIME)
                    .filter { it.rawValue.isNotBlank() }
                    .getOrNull(index = 0)
                    ?.rawValue
            val dateString = form.first(kind = InboundFieldKind.DATE)?.rawValue
            return if (timeString != null && dateString != null && isFutureTime(dateString, timeString)) {
                parseLocalDateTime(dateString = dateString, timeString = timeString)
            } else {
                null
            }
        }

        /**
         * Reads the second TIME picker (end) against the same DATE picker. Returns null when the
         * end-time picker is empty, when [startAt] is null, or when the date picker is missing.
         */
        private fun parseEndDateTime(form: InboundForm, startAt: LocalDateTime?): LocalDateTime? {
            if (startAt == null) return null
            val endTimeString =
                form
                    .all(kind = InboundFieldKind.TIME)
                    .filter { it.rawValue.isNotBlank() }
                    .getOrNull(index = 1)
                    ?.rawValue
                    ?: return null
            val dateString =
                form.first(kind = InboundFieldKind.DATE)?.rawValue
                    ?: return null
            return parseLocalDateTime(dateString = dateString, timeString = endTimeString)
        }

        private fun parseTitleAndReason(form: InboundForm): Pair<String, String> =
            form
                .all(kind = InboundFieldKind.TEXT)
                .takeIf { it.size >= 2 }
                ?.let {
                    val title = it[0].rawValue.ifBlank { DEFAULT_MEETING_TITLE }
                    val reason = it[1].rawValue.ifBlank { DEFAULT_MEETING_REASON }
                    title to reason
                } ?: (DEFAULT_MEETING_TITLE to DEFAULT_MEETING_REASON)

        private fun parseNoticeRequired(form: InboundForm): Boolean =
            form.first(kind = InboundFieldKind.TOGGLE)?.isSelected ?: false

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
