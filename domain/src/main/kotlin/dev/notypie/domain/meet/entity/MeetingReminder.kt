package dev.notypie.domain.meet.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import java.time.Instant

// SENT means enqueued to the outbox relay, not confirmed delivered by Slack.
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
