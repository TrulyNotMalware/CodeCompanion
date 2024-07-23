package dev.notypie.impl.command

import dev.notypie.domain.command.dto.interactions.InteractionPayloads

interface InteractionPayloadParser {
    fun parseStringContents(payload: String): InteractionPayloads
}