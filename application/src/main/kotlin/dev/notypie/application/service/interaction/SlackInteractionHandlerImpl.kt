package dev.notypie.application.service.interaction

import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class SlackInteractionHandlerImpl(
): InteractionHandler {

    override fun handleInteractions(headers: MultiValueMap<String, String>, payload: String) {
        TODO("Not yet implemented")
    }

    //FIXME response type BlockActionPayload class depends on Slack Sdk packages.
    private fun parseStringContents(payload: String){

    }
}