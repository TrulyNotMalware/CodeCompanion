package dev.notypie.application.common

import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

fun parseRequestBodyData(data: Map<String, String>): SlashCommandRequestBody =
    jsonMapper.convertValue(data, SlashCommandRequestBody::class.java)

fun parseRequestBodyData(
    headers: MultiValueMap<String, String>,
    data: Map<String, String>,
): Pair<SlashCommandRequestBody, InboundCommand> {
    val payload: SlashCommandRequestBody = parseRequestBodyData(data = data)
    return payload to payload.toInboundCommand()
}

inline fun <reified T : Any> Map<String, Any>.convert(): T = jsonMapper.convertValue(this, T::class.java)
