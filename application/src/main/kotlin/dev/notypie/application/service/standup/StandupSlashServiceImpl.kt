package dev.notypie.application.service.standup

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.entity.slash.SetupStandupCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap

@Service
class StandupSlashServiceImpl(
    private val commandExecutor: CommandExecutor,
) : StandupSlashService {
    @Transactional
    override fun handleStandup(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val command =
            SetupStandupCommand(
                idempotencyKey = idempotencyKey,
                commandData = commandData,
            )
        commandExecutor.execute(command = command)
    }
}
