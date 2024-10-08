package dev.notypie.application.service.slash

import org.springframework.util.MultiValueMap

interface SlashCommandHandler {

    fun handleMeetupRequest(headers: MultiValueMap<String, String>, data: Map<String, String>)
}