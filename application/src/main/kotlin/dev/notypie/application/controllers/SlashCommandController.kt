package dev.notypie.application.controllers

import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.application.service.meeting.MeetingService
import org.springframework.http.MediaType
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/slash")
class SlashCommandController(
    private val meetingService: MeetingService
) {

    @PostMapping(value = ["/meet"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun requestMeeting(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>
    ){
        val ( payload, slackCommandData ) = parseRequestBodyData(headers = headers, data = data)
        this.meetingService.handleNewMeeting( headers = headers,
            payload = payload, slackCommandData = slackCommandData )
    }

    @PostMapping(value = ["/task"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun requestTasks(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>
    ){
        val (payload, slackCommandData ) = parseRequestBodyData(headers = headers, data = data)
    }
}