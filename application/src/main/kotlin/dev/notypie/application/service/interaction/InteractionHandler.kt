package dev.notypie.application.service.interaction

import org.springframework.util.MultiValueMap

interface InteractionHandler {
    fun handleInteractions(headers: MultiValueMap<String, String>,payload: String)
}