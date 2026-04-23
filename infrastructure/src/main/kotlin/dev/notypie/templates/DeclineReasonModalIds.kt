package dev.notypie.templates

/**
 * Single source of truth for the block_id / action_id / callback_id strings used by the
 * decline-reason modal. Extracted out of [ModalTemplateBuilder] so the parser side
 * ([dev.notypie.impl.command.SlackInteractionRequestParser]) can reach them without a
 * dependency on the template builder's class, and so renames land in one place.
 */
object DeclineReasonModalIds {
    /** `callback_id` on the view envelope; routed to [DECLINE_REASON_BLOCK_ID] for state reads. */
    const val CALLBACK_ID: String = "decline_reason_modal"

    /** `block_id` wrapping the input block that holds the reason selector. */
    const val BLOCK_ID: String = "decline_reason_block"

    /**
     * `action_id` for the dropdown element inside [BLOCK_ID]. Kept stable across UI changes
     * (radio_buttons → static_select) so upgraded clients don't lose in-flight submissions.
     */
    const val ACTION_ID: String = "decline_reason_select"
}
