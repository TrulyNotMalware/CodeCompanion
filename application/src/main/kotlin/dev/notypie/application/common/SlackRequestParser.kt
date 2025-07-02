package dev.notypie.application.common

import dev.notypie.common.objectMapper
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import org.springframework.util.MultiValueMap


fun parseRequestBodyData(data: Map<String, String>): SlashCommandRequestBody =
    objectMapper.convertValue(data, SlashCommandRequestBody::class.java)

fun parseRequestBodyData(headers: MultiValueMap<String, String>, data: Map<String, String>): Pair<SlashCommandRequestBody, SlackCommandData> {
    val payload: SlashCommandRequestBody = parseRequestBodyData(data = data)
    return payload to payload.toSlackCommandData(rawHeader = SlackRequestHeaders(headers = headers), rawBody = data)
}

inline fun <reified T : Any> Map<String, Any>.convert(): T = objectMapper.convertValue(this, T::class.java)