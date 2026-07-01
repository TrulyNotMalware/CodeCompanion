package dev.notypie.application.service.standup

import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

interface StandupSlashService {
    fun handleStandup(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )
}
