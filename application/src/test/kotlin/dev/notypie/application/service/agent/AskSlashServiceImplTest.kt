package dev.notypie.application.service.agent

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createAgentQuestionMessage
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.slash.AskCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.util.LinkedMultiValueMap

class AskSlashServiceImplTest :
    BehaviorSpec({
        val denial = "You don't have permission to use the AI assistant. Ask an admin to grant you access."

        fun executedFor(commandData: InboundCommand, role: UserRole): Command<NoSubCommands> {
            val commandExecutor = mockk<CommandExecutor>()
            val executed = slot<Command<NoSubCommands>>()
            every { commandExecutor.execute(command = capture(executed)) } returns mockk(relaxed = true)
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = TEST_USER_ID) } returns role
            val service = AskSlashServiceImpl(commandExecutor = commandExecutor, commandRoleResolver = roleResolver)
            service.handleAsk(
                headers = LinkedMultiValueMap(),
                payload = mockk(relaxed = true),
                commandData = commandData,
            )
            return executed.captured
        }

        given("an /ask slash command") {
            val commandData = createSlashInboundCommand(subCommands = listOf("why", "is", "CI", "red?"))

            `when`("the asker's role resolves to AI_USER") {
                val command = executedFor(commandData = commandData, role = UserRole.AI_USER)

                then("exactly one AskCommand over that command data runs and posts the question to the channel") {
                    command.shouldBeInstanceOf<AskCommand>().commandData shouldBe commandData
                    command.handleEvent().ok shouldBe true
                    command.drainIntents().single() shouldBe createAgentQuestionMessage(prompt = "why is CI red?")
                }
            }

            `when`("the asker's role resolves to USER") {
                val command = executedFor(commandData = commandData, role = UserRole.USER)

                then("the resolved role reaches the domain, which denies the asker and posts nothing") {
                    command.handleEvent().ok shouldBe true
                    val ephemeral = command.drainIntents().single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content shouldBe MessageContent.Text(headline = null, markdown = denial)
                }
            }
        }
    })
