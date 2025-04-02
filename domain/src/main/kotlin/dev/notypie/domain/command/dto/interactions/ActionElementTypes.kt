package dev.notypie.domain.command.dto.interactions

enum class ActionElementTypes(
    val elementName: String,
    val isPrimary: Boolean,//Primary Element means that can run the events
) {
    APPLY_BUTTON("button", true),
    REJECT_BUTTON("button", true),
    BUTTON("button", true),
    MULTI_STATIC_SELECT("multi_static_select", false),
    MULTI_USERS_SELECT("multi_users_select", false),
    DATE_PICKER("datepicker", false),
    TIME_PICKER("timepicker", false),
    CHECKBOX("checkboxes", false),
    RADIO_BUTTONS("radio_buttons", false),
    PLAIN_TEXT_INPUT("plain_text_input", false),
    UNKNOWN("unknown", false)
}