package dev.notypie.domain.standup.entity.enums

/**
 * Lifecycle of a single [dev.notypie.domain.standup.entity.StandupSession]. The state machine
 * runs forward only:
 *
 *   COLLECTING (created at trigger time, awaiting answers)
 *     → SUMMARIZED (cutoff reached, channel summary posted)
 *     → SKIPPED   (terminal — admin/host explicitly cancelled, or routine was deactivated
 *                  before the cutoff, so we never post a summary)
 *
 * `SKIPPED` is distinct from `SUMMARIZED` so the channel-summary listener can refuse to
 * re-post for sessions the host meant to skip; both are terminal and never revert.
 */
enum class SessionStatus {
    COLLECTING,
    SUMMARIZED,
    SKIPPED,
}
