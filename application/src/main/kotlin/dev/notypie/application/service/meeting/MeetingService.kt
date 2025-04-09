package dev.notypie.application.service.meeting

import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

interface MeetingService {
    fun handleNewMeeting(headers: MultiValueMap<String, String>, payload: SlashCommandRequestBody, slackCommandData: SlackCommandData)
}