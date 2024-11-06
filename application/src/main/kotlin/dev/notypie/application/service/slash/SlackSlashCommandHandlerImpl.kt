package dev.notypie.application.service.slash

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.common.objectMapper
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap

@Service
class SlackSlashCommandHandlerImpl(
    private val slackApiRequester: SlackApiRequester,
): SlashCommandHandler {

    @Transactional
    override fun handleMeetupRequest(headers: MultiValueMap<String, String>, data: Map<String, String>) {
        val payload = this.parseBodyData(data = data)
        val slackCommandData = payload.toSlackCommandData(rawHeader = SlackRequestHeaders(underlying = headers), rawBody = data)
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command: Command = RequestMeetingCommand(
            commandData = slackCommandData, idempotencyKey = idempotencyKey, slackApiRequester = this.slackApiRequester
        )
        val result = command.handleEvent()
        
    }

    private fun parseBodyData(data: Map<String, String>): SlashCommandRequestBody =
        objectMapper.convertValue(data, SlashCommandRequestBody::class.java)
}