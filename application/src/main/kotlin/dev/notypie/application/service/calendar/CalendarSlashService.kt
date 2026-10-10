package dev.notypie.application.service.calendar

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.entity.slash.CalendarCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.impl.command.slack.SlashCommandRequestBody
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap

interface CalendarSlashService {
    fun handleCalendar(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    )
}

@Service
class CalendarSlashServiceImpl(
    private val commandExecutor: CommandExecutor,
) : CalendarSlashService {
    @Transactional
    override fun handleCalendar(
        headers: MultiValueMap<String, String>,
        payload: SlashCommandRequestBody,
        commandData: InboundCommand,
    ) {
        commandExecutor.execute(
            command =
                CalendarCommand(
                    idempotencyKey = IdempotencyCreator.create(data = commandData),
                    commandData = commandData,
                ),
        )
    }
}
