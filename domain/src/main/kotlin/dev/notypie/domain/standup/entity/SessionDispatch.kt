package dev.notypie.domain.standup.entity

import dev.notypie.domain.common.validate
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import java.time.Instant

/**
 * Tracks DM dispatch for a single member of a single [StandupSession]. One row per
 * `(sessionId, userId)` is created when the session opens; the scheduler claims the row
 * (PENDING→SENDING) and then flips it to [DispatchStatus.SENT] once the standup-prompt
 * `chat.postMessage` outbox row is persisted, or [DispatchStatus.FAILED] if message
 * construction or persistence raised an exception. Slack delivery itself is handled by
 * the existing outbox relay; SENT means "enqueued", not "Slack acknowledged".
 *
 * `dmTriggerAt` is the absolute UTC instant the DM should fire — pre-computed at session
 * creation time from the member's [RoutineMember.userTimezone] + the routine's
 * [Routine.triggerLocalTime] so the scheduler does not need to reason about timezones at
 * read time.
 */
class SessionDispatch(
    val userId: String,
    val dmTriggerAt: Instant,
    val dmSentAt: Instant? = null,
    val dmStatus: DispatchStatus = DispatchStatus.PENDING,
    val failureReason: String? = null,
) {
    init {
        validate(className = this.javaClass.simpleName) {
            notBlank {
                "userId" of userId
            }
            // The combinations the state machine forbids — caught here rather than at every
            // call site so JPA loads cannot resurrect logically-impossible rows.
            ("dmSentAt" of dmSentAt).shouldSatisfy("SENT dispatch must record dmSentAt") {
                dmStatus != DispatchStatus.SENT || it != null
            }
            ("failureReason" of failureReason).shouldSatisfy("FAILED dispatch must record a non-blank failureReason") {
                dmStatus != DispatchStatus.FAILED || !it.isNullOrBlank()
            }
        }
    }
}
