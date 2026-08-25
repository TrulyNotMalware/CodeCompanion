package dev.notypie.domain.standup.entity.enums

/**
 * Per-member DM dispatch state for a [dev.notypie.domain.standup.entity.SessionDispatch].
 *
 *   PENDING  (row created when the session opens, no DM sent yet)
 *     → SENDING (claimed by the scheduler via atomic CAS — prevents double-send on
 *                concurrent runs; a single tick claims the row exclusively before
 *                persisting the outbox message)
 *       → SENT   (Slack `chat.postMessage` outbox row was persisted — the standup-prompt
 *                  DM is en route via the existing relay pipeline)
 *       → FAILED (DM dispatch raised an exception before reaching the outbox; failureReason
 *                  captures the cause)
 *
 * `FAILED` is terminal at the dispatch level: the scheduler does not retry by transitioning
 * back to `PENDING`. A retry happens by creating a *new* dispatch row on the next session
 * tick, so a failed attempt always leaves an audit trail of why the user did not receive the
 * standup prompt.
 *
 * `SENDING` rows older than the configured threshold (e.g. 5 minutes) are considered
 * stuck and recovered by resetting to `PENDING` so a later scheduler tick can re-claim them.
 */
enum class DispatchStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
}
