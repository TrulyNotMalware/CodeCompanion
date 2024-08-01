package dev.notypie.impl.command

import dev.notypie.domain.command.dto.interactions.InteractionPayload

interface InteractionPayloadParser {
    fun parseStringContents(payload: String): InteractionPayload
}