package dev.notypie.domain.meet.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import java.time.Instant

/**
 * Tracks a single pre-meeting reminder DM batch for one meeting at one offset. One row per
 * `(meetingId, offsetMinutes)` is materialized when the meeting enters the scheduler's forward
 * window; the scheduler claims the row (PENDING→SENDING) and then flips it to
 * [MeetingReminderStatus.SENT] once a reminder `chat.postMessage` outbox row has been persisted
 * for every attending participant, or [MeetingReminderStatus.FAILED] if message construction or
 * persistence raised an exception. Slack delivery itself is handled by the existing outbox relay;
 * SENT means "enqueued", not "Slack acknowledged".
 *
 * `scheduledAt` is the absolute UTC instant the reminder should fire — pre-computed at
 * materialization time as `meeting.startAt - offsetMinutes` so the scheduler does not need to
 * re-derive the fire time at read time.
 */
class MeetingReminder(
    val offsetMinutes: Int,
    val scheduledAt: Instant,
    val sentAt: Instant? = null,
    val status: MeetingReminderStatus = MeetingReminderStatus.PENDING,
    val failureReason: String? = null,
) {
    init {
        validate(className = javaClass.simpleName) {
            "offsetMinutes" of offsetMinutes shouldBeGreaterThan 0
            // The combinations the state machine forbids — caught here rather than at every
            // call site so JPA loads cannot resurrect logically-impossible rows.
            ("sentAt" of sentAt).shouldSatisfy("SENT reminder must record sentAt") {
                status != MeetingReminderStatus.SENT || it != null
            }
            ("failureReason" of failureReason).shouldSatisfy(
                "FAILED reminder must record a non-blank failureReason",
            ) {
                status != MeetingReminderStatus.FAILED || !it.isNullOrBlank()
            }
        }
    }
}
