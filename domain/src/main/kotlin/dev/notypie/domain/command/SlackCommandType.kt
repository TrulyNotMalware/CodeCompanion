package dev.notypie.domain.command

import dev.notypie.domain.command.dto.UrlVerificationRequest
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import kotlin.reflect.KClass

enum class SlackCommandType(
    val convertType: KClass<*>
) {
    URL_VERIFICATION(UrlVerificationRequest::class),
    EVENT_CALLBACK(Nothing::class),
    //Subtype of event call back
    APP_MENTION(SlackEventCallBackRequest::class)
}