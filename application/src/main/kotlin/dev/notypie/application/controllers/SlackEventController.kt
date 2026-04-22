package dev.notypie.application.controllers

import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.domain.command.SlackCommandType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/slack")
class SlackEventController(
    private val eventHandler: AppMentionEventHandler,
    private val interactionHandler: InteractionHandler,
) {
    companion object {
        private const val APP_MENTION_EVENT_TYPE = "app_mention"
    }

    @PostMapping(value = ["/events"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun handleAppMentionEvents(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestBody payload: Map<String, Any>,
    ): ResponseEntity<*> {
        if (isChallengeRequest(payload = payload)) return ResponseEntity.ok().body(payload) // FIXME logging.

        // Slack Events API delivers MANY event types to this single webhook (app_mention,
        // message.im, message_changed, reaction_added, etc.). Some of them carry payloads
        // with no `event.user` field (e.g. message subtypes like `message_deleted`), which
        // would trip strict non-nullable Kotlin deserialization downstream. We only process
        // `app_mention`; everything else gets acknowledged (200 OK) so Slack does not retry
        // and our non-app_mention deserialization does not crash.
        val eventType = extractEventType(payload = payload)
        if (eventType != APP_MENTION_EVENT_TYPE) {
            logger.debug { "Ignoring non-app_mention Slack event: type=$eventType" }
            return ResponseEntity.ok().build<Unit>()
        }

        val slackCommandData = eventHandler.handleEvent(headers = headers, payload = payload)
        return ResponseEntity.ok().body(slackCommandData)
    }

    @PostMapping(value = ["/interaction"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun handleInteractions(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam payload: String,
    ): ResponseEntity<*> {
        interactionHandler.handleInteraction(headers = headers, payload = payload)
        return ResponseEntity.ok().body("")
    }

    private fun isChallengeRequest(payload: Map<String, Any>) =
        payload["type"] == SlackCommandType.URL_VERIFICATION.toString().lowercase()

    private fun extractEventType(payload: Map<String, Any>): String? {
        val event = payload["event"] as? Map<*, *> ?: return null
        return event["type"] as? String
    }
}
