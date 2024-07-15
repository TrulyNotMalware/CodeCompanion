package dev.notypie.application.controllers

import dev.notypie.application.service.interaction.InteractionHandler
import dev.notypie.application.service.mention.AppMentionEventHandler
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/slack")
class SlackEventController(
    private val eventHandler: AppMentionEventHandler,
    private val interactionHandler: InteractionHandler
) {

    @PostMapping(value = ["/events"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun handleAppMentionEvents(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestBody payload: Map<String, Any>
    ): ResponseEntity<*> {
        val slackCommandData = this.eventHandler.handleEvent(headers = headers, payload = payload)
        return ResponseEntity.ok().body(slackCommandData)
    }

    @PostMapping(value = ["/interaction"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun handleInteractions(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam payload: String
    ): ResponseEntity<*> {
        return ResponseEntity.ok().body("")
    }
}