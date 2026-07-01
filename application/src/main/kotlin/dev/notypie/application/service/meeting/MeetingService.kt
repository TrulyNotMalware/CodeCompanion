package dev.notypie.application.service.meeting

import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

interface MeetingService {
    fun handleMeeting(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )
}
