package dev.notypie.application.service.agent

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.domain.command.entity.slash.AskCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap

interface AskSlashService {
    fun handleAsk(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )
}

@Service
class AskSlashServiceImpl(
    private val commandExecutor: CommandExecutor,
    private val commandRoleResolver: CommandRoleResolver,
) : AskSlashService {
    @Transactional
    override fun handleAsk(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        commandExecutor.execute(
            command =
                AskCommand(
                    idempotencyKey = IdempotencyCreator.create(data = commandData),
                    commandData = commandData,
                    actorRole = commandRoleResolver.resolve(userId = commandData.actorId),
                ),
        )
    }
}
