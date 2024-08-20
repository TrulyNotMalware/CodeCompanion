package dev.notypie.application.service.interaction

import com.fasterxml.jackson.databind.ObjectMapper
import dev.notypie.domain.history.repository.HistoryRepository
import dev.notypie.impl.command.InteractionPayloadParser
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class SlackInteractionHandlerImpl(
    private val objectMapper: ObjectMapper,
    private val interactionPayloadParser: InteractionPayloadParser,
    private val historyRepository: HistoryRepository
): InteractionHandler {

    override fun handleInteractions(headers: MultiValueMap<String, String>, payload: String) {
        val interactionPayload = this.interactionPayloadParser.parseStringContents(payload = payload)
        println(payload)
        println(interactionPayload)
    }



}