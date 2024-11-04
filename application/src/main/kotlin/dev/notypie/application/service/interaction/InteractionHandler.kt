package dev.notypie.application.service.interaction

import org.springframework.util.MultiValueMap

interface InteractionHandler {
    fun handleInteraction(headers: MultiValueMap<String, String>, payload: String)
}