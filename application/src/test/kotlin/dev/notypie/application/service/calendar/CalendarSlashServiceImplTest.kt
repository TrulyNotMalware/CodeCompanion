package dev.notypie.application.service.calendar

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.slash.CalendarCommand
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.util.LinkedMultiValueMap

class CalendarSlashServiceImplTest :
    BehaviorSpec({
        given("a /calendar slash command") {
            val commandExecutor = mockk<CommandExecutor>()
            val executed = slot<Command<NoSubCommands>>()
            every { commandExecutor.execute(command = capture(executed)) } returns mockk(relaxed = true)
            val commandData = createSlashInboundCommand(subCommands = listOf("status"))

            `when`("the service handles it") {
                CalendarSlashServiceImpl(commandExecutor = commandExecutor).handleCalendar(
                    headers = LinkedMultiValueMap(),
                    payload = mockk(relaxed = true),
                    commandData = commandData,
                )

                then("exactly one CalendarCommand over that command data runs through the executor") {
                    val command = executed.captured.shouldBeInstanceOf<CalendarCommand>()
                    command.commandData shouldBe commandData
                    command.handleEvent().ok shouldBe true
                    command.drainIntents().single().shouldBeInstanceOf<CommandIntent.CalendarStatus>()
                }
            }
        }
    })
