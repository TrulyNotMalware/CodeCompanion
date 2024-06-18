package dev.notypie.domain.command

enum class SlackCommandType {
    URL_VERIFICATION,
    EVENT_CALLBACK,
    //Subtype of event call back
    APP_MENTION
}