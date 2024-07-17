package dev.notypie.impl.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload

class SlackInteractionRequestParser(
    private val objectMapper: ObjectMapper,
) : InteractionPayloadParser {
    
    override fun parseStringContents(payload: String) {
        TODO("not implemented")
    }


}