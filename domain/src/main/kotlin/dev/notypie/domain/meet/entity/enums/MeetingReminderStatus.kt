package dev.notypie.domain.meet.entity.enums

/**
 * Per-meeting, per-offset reminder dispatch state for a
 * [dev.notypie.domain.meet.entity.MeetingReminder].
 *
 *   PENDING  (row materialized when the meeting enters the forward window, no DM sent yet)
 *     → SENDING (claimed by the scheduler via atomic CAS — prevents double-send on
 *                concurrent runs; a single tick claims the row exclusively before
 *                persisting the outbox messages)
 *       → SENT   (Slack `chat.postMessage` outbox rows were persisted — the reminder DMs
 *                  are en route via the existing relay pipeline)
 *       → FAILED (DM dispatch raised an exception before reaching the outbox; failureReason
 *                  captures the cause)
 *
 * `FAILED` is terminal at the reminder level: the scheduler does not retry by transitioning
 * back to `PENDING`. The unique constraint `(meeting_id, offset_minutes)` guarantees one
 * reminder attempt per offset, so a failed attempt always leaves an audit trail.
 *
 * `SENDING` rows older than the configured threshold (e.g. 5 minutes) are considered stuck
 * and recovered by resetting to `PENDING` so a later scheduler tick can re-claim them.
 */
enum class MeetingReminderStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
}
