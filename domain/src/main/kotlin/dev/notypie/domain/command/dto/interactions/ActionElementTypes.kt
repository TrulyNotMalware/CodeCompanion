package dev.notypie.domain.command.dto.interactions

enum class ActionElementTypes(
    val elementName: String,
    val isPrimary: Boolean,//Primary Element means that can run the events
) {
    BUTTON("button", true),
    MULTI_STATIC_SELECT("multi_static_select", false),
    MULTI_USERS_SELECT("multi_users_select", false),
    UNKNOWN("unknown", false)
}