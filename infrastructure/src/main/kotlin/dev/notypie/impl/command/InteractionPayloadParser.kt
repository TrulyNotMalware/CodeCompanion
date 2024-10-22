package dev.notypie.impl.command

import dev.notypie.domain.command.dto.interactions.InteractionPayload

interface InteractionPayloadParser {
    fun parseStringPayload(payload: String): InteractionPayload
}