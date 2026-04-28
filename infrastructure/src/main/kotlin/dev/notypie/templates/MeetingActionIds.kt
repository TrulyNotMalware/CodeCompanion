package dev.notypie.templates

/**
 * Single source of truth for the block_id / action_id strings used by interactive
 * elements rendered alongside meetings (e.g. the inline Cancel button on `/meetup list`).
 * Mirrors the [DeclineReasonModalIds] convention so renames land in one place and the
 * parser side never has to import templating internals.
 */
object MeetingActionIds {
    /** `block_id` wrapping the cancel-button actions block on `/meetup list` rows. */
    const val CANCEL_BLOCK_ID: String = "meeting_cancel_block"

    /** `action_id` for the danger-style cancel button inside [CANCEL_BLOCK_ID]. */
    const val CANCEL_ACTION_ID: String = "meeting_cancel_button"
}
