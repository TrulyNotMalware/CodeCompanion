package dev.notypie.application.service.standup

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.slash.SlashCommandRequestBody
import dev.notypie.domain.command.entity.slash.SetupStandupCommand
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

/**
 * Slash entry point for `/standup setup`. Mirrors [dev.notypie.application.service.meeting.MeetingServiceImpl]:
 * builds the [SetupStandupCommand] from the parsed slash body and hands it to the
 * [CommandExecutor], which runs the context pipeline and resolves the emitted intents
 * (the synchronous `views.open` for the setup modal).
 */
@Service
class StandupSlashServiceImpl(
    private val commandExecutor: CommandExecutor,
) : StandupSlashService {
    @Transactional
    override fun handleStandup(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        slackCommandData: SlackCommandData,
    ) {
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command =
            SetupStandupCommand(
                idempotencyKey = idempotencyKey,
                commandData = slackCommandData,
            )
        commandExecutor.execute(command = command)
    }
}
