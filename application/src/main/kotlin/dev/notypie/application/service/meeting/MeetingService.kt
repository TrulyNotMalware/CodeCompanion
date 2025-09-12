package dev.notypie.application.service.meeting

import dev.notypie.application.controllers.dto.GetMeetupListRequestDto
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

interface MeetingService {
    fun handleMeeting(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        slackCommandData: SlackCommandData,
    )

    fun getMyMeetingList(meetingRequestDto: GetMeetupListRequestDto)
}
