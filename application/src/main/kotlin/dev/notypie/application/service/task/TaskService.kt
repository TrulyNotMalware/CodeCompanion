package dev.notypie.application.service.task

import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import org.springframework.util.MultiValueMap

interface TaskService {
    fun handleTask(headers: MultiValueMap<String, String>, payload: SlashCommandRequestBody, slackCommandData: SlackCommandData)
}