package dev.notypie.application.controllers

import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.mention.AppMentionEventHandler
import dev.notypie.impl.command.slack.SlackEventType
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

        // Some event types lack event.user and would crash deserialization; only app_mention is processed.
        val eventType = extractEventType(payload = payload)
        if (eventType != APP_MENTION_EVENT_TYPE) {
            logger.debug { "Ignoring non-app_mention Slack event: type=$eventType" }
            return ResponseEntity.ok().build<Unit>()
        }

        val commandData = eventHandler.handleEvent(headers = headers, payload = payload)
        return ResponseEntity.ok().body(commandData)
    }

    @PostMapping(value = ["/interaction"])
    fun handleInteractions(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam payload: String,
    ): ResponseEntity<*> {
        val ackBody = interactionHandler.handleInteraction(headers = headers, payload = payload)
        return if (ackBody != null) {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ackBody)
        } else {
            ResponseEntity.ok().body("")
        }
    }

    private fun isChallengeRequest(payload: Map<String, Any>) =
        payload["type"] == SlackEventType.URL_VERIFICATION.toString().lowercase()

    private fun extractEventType(payload: Map<String, Any>): String? {
        val event = payload["event"] as? Map<*, *> ?: return null
        return event["type"] as? String
    }
}
