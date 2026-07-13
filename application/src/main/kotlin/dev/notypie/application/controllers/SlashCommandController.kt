package dev.notypie.application.controllers

import dev.notypie.application.common.parseRequestBodyData
import dev.notypie.application.service.cve.subscription.CveSubscriptionSlashService
import dev.notypie.application.service.meeting.MeetingService
import dev.notypie.application.service.standup.StandupSlashService
import org.springframework.http.MediaType
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/slash")
class SlashCommandController(
    private val meetingService: MeetingService,
    private val standupSlashService: StandupSlashService,
    private val cveSubscriptionSlashService: CveSubscriptionSlashService,
) {
    @PostMapping(value = ["/meet"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun requestMeeting(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>,
    ) {
        val (payload, commandData) = parseRequestBodyData(headers = headers, data = data)
        meetingService.handleMeeting(
            headers = headers,
            payload = payload,
            commandData = commandData,
        )
    }

    @PostMapping(value = ["/standup"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun setupStandup(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>,
    ) {
        val (payload, commandData) = parseRequestBodyData(headers = headers, data = data)
        standupSlashService.handleStandup(
            headers = headers,
            payload = payload,
            commandData = commandData,
        )
    }

    @PostMapping(value = ["/task"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun requestTasks(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>,
    ) {
        val (payload, commandData) = parseRequestBodyData(headers = headers, data = data)
    }

    @PostMapping(value = ["/subscribe"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun subscribe(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>,
    ) {
        val (payload, commandData) = parseRequestBodyData(headers = headers, data = data)
        cveSubscriptionSlashService.handleSubscribe(
            headers = headers,
            payload = payload,
            commandData = commandData,
        )
    }

    @PostMapping(value = ["/unsubscribe"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun unsubscribe(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>,
    ) {
        val (payload, commandData) = parseRequestBodyData(headers = headers, data = data)
        cveSubscriptionSlashService.handleUnsubscribe(
            headers = headers,
            payload = payload,
            commandData = commandData,
        )
    }

    @PostMapping(value = ["/subscriptions"], produces = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun subscriptions(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam data: Map<String, String>,
    ) {
        val (payload, commandData) = parseRequestBodyData(headers = headers, data = data)
        cveSubscriptionSlashService.handleSubscriptions(
            headers = headers,
            payload = payload,
            commandData = commandData,
        )
    }
}
