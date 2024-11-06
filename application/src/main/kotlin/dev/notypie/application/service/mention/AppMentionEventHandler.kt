package dev.notypie.application.service.mention

import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
import org.springframework.util.MultiValueMap

interface AppMentionEventHandler {
    fun parseAppMentionEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): SlackCommandData
    fun handleEvent(slackCommandData: SlackCommandData): CommandOutput
    fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): CommandOutput
}