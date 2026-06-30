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
 * Identifiers for the standup-setup modal opened from `/standup setup`. Each `BLOCK_ID` is
 * matched verbatim by [dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext]
 * (which keeps its own copy of these strings, since the domain module can't depend on templating).
 * Keep the two in sync.
 */
object StandupSetupModalIds {
    const val CALLBACK_ID: String = "standup_setup_modal"

    const val NAME_BLOCK_ID: String = "standup_setup_name"
    const val NAME_ACTION_ID: String = "standup_setup_name_input"

    const val QUESTIONS_BLOCK_ID: String = "standup_setup_questions"
    const val QUESTIONS_ACTION_ID: String = "standup_setup_questions_input"

    const val MEMBERS_BLOCK_ID: String = "standup_setup_members"
    const val MEMBERS_ACTION_ID: String = "standup_setup_members_select"

    const val SUMMARY_CHANNEL_BLOCK_ID: String = "standup_setup_summary_channel"
    const val SUMMARY_CHANNEL_ACTION_ID: String = "standup_setup_summary_channel_select"

    const val WEEKDAYS_BLOCK_ID: String = "standup_setup_weekdays"
    const val WEEKDAYS_ACTION_ID: String = "standup_setup_weekdays_select"

    const val TIME_BLOCK_ID: String = "standup_setup_time"
    const val TIME_ACTION_ID: String = "standup_setup_time_picker"

    const val CUTOFF_BLOCK_ID: String = "standup_setup_cutoff"
    const val CUTOFF_ACTION_ID: String = "standup_setup_cutoff_input"

    const val TIMEZONE_BLOCK_ID: String = "standup_setup_timezone"
    const val TIMEZONE_ACTION_ID: String = "standup_setup_timezone_select"
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

    // Optional free-text detail, required only when the selected reason is OTHER. The block id is
    // the key returned in a view_submission `response_action: errors` payload so the inline
    // "please explain" message attaches to this field.
    const val DETAIL_BLOCK_ID: String = "decline_reason_detail_block"
    const val DETAIL_ACTION_ID: String = "decline_reason_detail_input"
}

/**
 * Identifiers for the inline Cancel/Reschedule buttons rendered alongside `/meetup list` rows.
 * Mirrors the modal-id naming convention so the parser side never has to import templating
 * internals.
 */
object MeetingActionIds {
    const val CANCEL_BLOCK_ID: String = "meeting_cancel_block"
    const val CANCEL_ACTION_ID: String = "meeting_cancel_button"

    const val RESCHEDULE_BLOCK_ID: String = "meeting_reschedule_block"
    const val RESCHEDULE_ACTION_ID: String = "meeting_reschedule_button"

    const val ADD_PARTICIPANT_ACTION_ID: String = "meeting_add_participant_button"
}

/**
 * Identifiers for the add-participant modal opened when a host clicks "Add participant" on
 * `/meetup list`. The multi-users select is read back by
 * [dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext], which keeps its
 * own copy of [USERS_BLOCK_ID] (the domain module can't depend on templating). Keep the two in sync.
 */
object AddParticipantModalIds {
    const val CALLBACK_ID: String = "add_participant_modal"

    const val USERS_BLOCK_ID: String = "add_participant_users"
    const val USERS_ACTION_ID: String = "add_participant_users_select"
}

/**
 * Identifiers for the reschedule modal opened when a host clicks Reschedule on `/meetup list`.
 * The DATE_PICKER + TIME_PICKER pair is read back by
 * [dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext].
 */
object RescheduleMeetingModalIds {
    const val CALLBACK_ID: String = "reschedule_meeting_modal"

    const val DATE_BLOCK_ID: String = "reschedule_meeting_date"
    const val DATE_ACTION_ID: String = "reschedule_meeting_date_picker"

    const val TIME_BLOCK_ID: String = "reschedule_meeting_time"
    const val TIME_ACTION_ID: String = "reschedule_meeting_time_picker"
}
