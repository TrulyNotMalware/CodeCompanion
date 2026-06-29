package dev.notypie.application.service.standup

import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

interface StandupSlashService {
    fun handleStandup(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        slackCommandData: SlackCommandData,
    )
}
