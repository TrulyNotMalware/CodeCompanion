package dev.notypie.application.service.mention

import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.SlackApiResponse
import org.springframework.util.MultiValueMap

interface AppMentionEventHandler {
    fun parseAppMentionEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): SlackCommandData
    fun handleEvent(slackCommandData: SlackCommandData): SlackApiResponse
    fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): SlackApiResponse
}