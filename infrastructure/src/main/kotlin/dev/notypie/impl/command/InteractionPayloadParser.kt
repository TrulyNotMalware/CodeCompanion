package dev.notypie.impl.command

import dev.notypie.impl.command.slack.InteractionPayload

interface InteractionPayloadParser {
    fun parseStringPayload(payload: String): InteractionPayload
}
