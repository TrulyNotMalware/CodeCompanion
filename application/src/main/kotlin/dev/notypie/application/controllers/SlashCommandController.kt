package dev.notypie.application.controllers

import dev.notypie.application.service.slash.SlashCommandHandler
import org.springframework.http.MediaType
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/slash")
class SlashCommandController(
    private val slashCommandHandler: SlashCommandHandler
) {

    @PostMapping(value = ["/meet"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun requestMeeting(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>
    ){
        this.slashCommandHandler.handleMeetupRequest(headers = headers, data = data)
    }
}