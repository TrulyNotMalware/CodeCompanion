package dev.notypie.templates

import dev.notypie.domain.command.inbound.InboundFieldKeys

// Names must match Slack Block Kit style values exactly (lowercased elsewhere via .toString().lowercase()).
enum class ButtonType {
    PRIMARY,
    DEFAULT,
    DANGER,
}

object StandupModalIds {
    const val CALLBACK_ID: String = "standup_answer_modal"
    const val BLOCK_ID_PREFIX: String = InboundFieldKeys.STANDUP_ANSWER_QUESTION_PREFIX
    const val ACTION_ID_PREFIX: String = "standup_answer_"
}

object StandupSetupModalIds {
    const val CALLBACK_ID: String = "standup_setup_modal"

    const val NAME_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_NAME
    const val NAME_ACTION_ID: String = "standup_setup_name_input"

    const val QUESTIONS_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_QUESTIONS
    const val QUESTIONS_ACTION_ID: String = "standup_setup_questions_input"

    const val MEMBERS_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_MEMBERS
    const val MEMBERS_ACTION_ID: String = "standup_setup_members_select"

    const val SUMMARY_CHANNEL_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_SUMMARY_CHANNEL
    const val SUMMARY_CHANNEL_ACTION_ID: String = "standup_setup_summary_channel_select"

    const val WEEKDAYS_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_WEEKDAYS
    const val WEEKDAYS_ACTION_ID: String = "standup_setup_weekdays_select"

    const val TIME_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_TIME
    const val TIME_ACTION_ID: String = "standup_setup_time_picker"

    const val CUTOFF_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_CUTOFF
    const val CUTOFF_ACTION_ID: String = "standup_setup_cutoff_input"

    const val TIMEZONE_BLOCK_ID: String = InboundFieldKeys.STANDUP_SETUP_TIMEZONE
    const val TIMEZONE_ACTION_ID: String = "standup_setup_timezone_select"
}

object CveSubscriptionModalIds {
    const val SUBSCRIBE_CALLBACK_ID: String = "cve_subscribe_modal"
    const val SUBSCRIBE_TOPICS_BLOCK_ID: String = InboundFieldKeys.CVE_SUBSCRIBE_TOPICS
    const val SUBSCRIBE_TOPICS_ACTION_ID: String = "cve_subscribe_topics_select"

    const val UNSUBSCRIBE_CALLBACK_ID: String = "cve_unsubscribe_modal"
    const val UNSUBSCRIBE_TOPICS_BLOCK_ID: String = InboundFieldKeys.CVE_UNSUBSCRIBE_TOPICS
    const val UNSUBSCRIBE_TOPICS_ACTION_ID: String = "cve_unsubscribe_topics_select"
}

// ACTION_ID stays stable across UI changes (radio_buttons→static_select) so in-flight submissions aren't lost.
object DeclineReasonModalIds {
    const val CALLBACK_ID: String = "decline_reason_modal"
    const val BLOCK_ID: String = "decline_reason_block"
    const val ACTION_ID: String = "decline_reason_select"

    // block_id is the key Slack's response_action:errors payload uses to attach the "explain" error inline.
    const val DETAIL_BLOCK_ID: String = "decline_reason_detail_block"
    const val DETAIL_ACTION_ID: String = "decline_reason_detail_input"
}

object MeetingActionIds {
    const val CANCEL_BLOCK_ID: String = "meeting_cancel_block"
    const val CANCEL_ACTION_ID: String = "meeting_cancel_button"

    const val RESCHEDULE_BLOCK_ID: String = "meeting_reschedule_block"
    const val RESCHEDULE_ACTION_ID: String = "meeting_reschedule_button"

    const val ADD_PARTICIPANT_ACTION_ID: String = "meeting_add_participant_button"
}

object AddParticipantModalIds {
    const val CALLBACK_ID: String = "add_participant_modal"

    const val USERS_BLOCK_ID: String = InboundFieldKeys.ADD_PARTICIPANT_USERS
    const val USERS_ACTION_ID: String = "add_participant_users_select"
}

object RescheduleMeetingModalIds {
    const val CALLBACK_ID: String = "reschedule_meeting_modal"

    const val DATE_BLOCK_ID: String = "reschedule_meeting_date"
    const val DATE_ACTION_ID: String = "reschedule_meeting_date_picker"

    const val TIME_BLOCK_ID: String = "reschedule_meeting_time"
    const val TIME_ACTION_ID: String = "reschedule_meeting_time_picker"
}
