package dev.notypie.templates

/*
 * Single source of truth for the `block_id` / `action_id` / `callback_id` strings used by
 * the interactive components rendered through [ModalTemplateBuilder] and consumed by
 * [dev.notypie.impl.command.SlackInteractionRequestParser]. Keeping them co-located
 * — instead of one tiny file per modal — makes renames atomic and removes the temptation
 * to re-derive the prefixes on the parser side.
 */

/** Style enum on Slack `button` elements; mirrors the Slack Block Kit `style` values. */
enum class ButtonType {
    PRIMARY,
    DEFAULT,
    DANGER,
}

/**
 * Identifiers for the standup answer modal opened from a "Fill in standup" DM button.
 * `BLOCK_ID_PREFIX`/`ACTION_ID_PREFIX` are joined with the question index at render time.
 */
object StandupModalIds {
    const val CALLBACK_ID: String = "standup_answer_modal"
    const val BLOCK_ID_PREFIX: String = "standup_q_"
    const val ACTION_ID_PREFIX: String = "standup_answer_"
}

/**
 * Identifiers for the decline-reason modal opened when a meeting participant clicks Deny.
 * `ACTION_ID` is kept stable across UI changes (radio_buttons → static_select) so upgraded
 * clients don't lose in-flight submissions.
 */
object DeclineReasonModalIds {
    const val CALLBACK_ID: String = "decline_reason_modal"
    const val BLOCK_ID: String = "decline_reason_block"
    const val ACTION_ID: String = "decline_reason_select"
}

/**
 * Identifiers for the inline Cancel button rendered alongside `/meetup list` rows. Mirrors
 * the modal-id naming convention so the parser side never has to import templating internals.
 */
object MeetingActionIds {
    const val CANCEL_BLOCK_ID: String = "meeting_cancel_block"
    const val CANCEL_ACTION_ID: String = "meeting_cancel_button"
}
