package dev.notypie.application.service.mention

import dev.notypie.domain.command.dto.SlackCommandData
import org.springframework.util.MultiValueMap

interface AppMentionEventHandler {
    fun parseAppMentionEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): SlackCommandData
}