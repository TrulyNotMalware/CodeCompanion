package dev.notypie.application.common

import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody

fun parseRequestBodyData(data: Map<String, String>): Pair<SlashCommandRequestBody, InboundCommand> {
    val payload: SlashCommandRequestBody = jsonMapper.convertValue(data, SlashCommandRequestBody::class.java)
    return payload to payload.toInboundCommand()
}
