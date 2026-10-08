package dev.notypie.domain.command.entity

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.TestCommand
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createInteractionResponseInboundCommand
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.findSubCommandByIdentifier
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class CommandTest :
    BehaviorSpec({

        given("Command.handleEvent with non-interaction command") {
            val commandData = createMentionInboundCommand()
            val idempotencyKey = UUID.randomUUID()

            val command =
                object : Command<NoSubCommands>(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                ) {
                    override fun parseContext(
                        subCommand: SubCommand<NoSubCommands>,
                    ): CommandContext<out NoSubCommands> =
                        EmptyContext(
                            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                            intents = intents,
                        )

                    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
                }

            `when`("handleEvent succeeds") {
                val result = command.handleEvent()

                then("should return CommandOutput from context.runCommand") {
                    result.ok shouldBe false
                }
            }
        }

        given("Command.handleEvent with interaction command") {
            val idempotencyKey = UUID.randomUUID()
            val interactionPayload =
                createInboundInteraction(
                    detailType = CommandDetailType.APPROVAL_REQUEST,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = idempotencyKey,
                )
            val commandData = createInteractionResponseInboundCommand(interaction = interactionPayload)

            `when`("context is ReactionContext") {
                val command =
                    object : Command<NoSubCommands>(
                        idempotencyKey = idempotencyKey,
                        commandData = commandData,
                    ) {
                        override fun parseContext(
                            subCommand: SubCommand<NoSubCommands>,
                        ): CommandContext<out NoSubCommands> =
                            object : ReactionContext<NoSubCommands>(
                                commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                                subCommand = subCommand,
                                intents = intents,
                            ) {
                                override fun parseCommandType() = CommandType.SIMPLE

                                override fun parseCommandDetailType() = CommandDetailType.APPROVAL_REQUEST

                                override fun runCommand() = CommandOutput.empty()
                            }

                        override fun findSubCommandDefinition() = NoSubCommands()
                    }

                val result = command.handleEvent()

                then("should return success from handleInteraction") {
                    result.ok shouldBe true
                }
            }

            `when`("context is not ReactionContext") {
                val command =
                    object : Command<NoSubCommands>(
                        idempotencyKey = idempotencyKey,
                        commandData = commandData,
                    ) {
                        override fun parseContext(
                            subCommand: SubCommand<NoSubCommands>,
                        ): CommandContext<out NoSubCommands> =
                            EmptyContext(
                                commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                                intents = intents,
                            )

                        override fun findSubCommandDefinition() = NoSubCommands()
                    }

                val result = command.handleEvent()

                then("should return fail output with error reason") {
                    result.ok shouldBe false
                    result.status shouldBe Status.FAILED
                    result.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                }
            }
        }

        given("Command.handleEvent when executeCommand is interrupted or hits an Error") {
            fun commandThrowing(failure: Throwable) =
                object : Command<NoSubCommands>(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createMentionInboundCommand(),
                ) {
                    override fun parseContext(
                        subCommand: SubCommand<NoSubCommands>,
                    ): CommandContext<out NoSubCommands> = throw failure

                    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
                }

            `when`("an InterruptedException is thrown") {
                val outcome = runCatching { commandThrowing(failure = InterruptedException("shutdown")).handleEvent() }
                val keptInterrupt = Thread.interrupted()

                then("it propagates with the interrupt flag instead of becoming an error reply") {
                    (outcome.exceptionOrNull() is InterruptedException) shouldBe true
                    keptInterrupt shouldBe true
                }
            }

            `when`("an Error is thrown") {
                val outcome = runCatching { commandThrowing(failure = NotImplementedError("simulated")).handleEvent() }

                then("it propagates instead of becoming an error reply") {
                    (outcome.exceptionOrNull() is NotImplementedError) shouldBe true
                }
            }
        }

        given("Command.handleEvent when executeCommand throws") {
            val commandData = createMentionInboundCommand()
            val idempotencyKey = UUID.randomUUID()

            val command =
                object : Command<NoSubCommands>(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                ) {
                    override fun parseContext(
                        subCommand: SubCommand<NoSubCommands>,
                    ): CommandContext<out NoSubCommands> = throw RuntimeException("Test exception")

                    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
                }

            `when`("handleEvent catches the exception") {
                val result = command.handleEvent()

                then("should return fail output") {
                    result.ok shouldBe false
                    result.status shouldBe Status.FAILED
                    result.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                }

                then("error reason should contain the exception message") {
                    result.errorReason.contains("Test exception") shouldBe true
                }
            }
        }

        given("a slash command that cannot handle its input") {
            fun slashData(vararg tokens: String) = createSlashInboundCommand(subCommands = tokens.toList())

            fun replyOf(command: Command<*>): Pair<CommandOutput, String> {
                val output = command.handleEvent()
                val ephemeral = command.drainIntents().single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                ephemeral.target shouldBe ConversationTarget(id = TEST_CHANNEL_ID)
                ephemeral.recipient shouldBe null
                return output to ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown
            }

            `when`("the subcommand is unknown and the echoed token carries markup and runs long") {
                val token = "<!channel>&`" + "x".repeat(n = 60)
                val (output, markdown) = replyOf(command = ProbeCommand(commandData = slashData(token)))

                then("the requester gets the escaped, clipped token and the command's usage lines") {
                    output.ok shouldBe false
                    output.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                    output.errorReason shouldContain "SubCommandParseException"
                    markdown shouldBe
                        "Unknown subcommand `&lt;!channel&gt;&amp;'${"x".repeat(n = 28)}…`.\n" +
                        "Usage:\n• `/probe`\n• `/probe run &lt;job&gt;`"
                }
            }

            `when`("the subcommand misses the arguments it requires") {
                val (output, markdown) = replyOf(command = ProbeCommand(commandData = slashData("run")))

                then("the requester gets that subcommand's usage") {
                    output.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                    markdown shouldBe "Usage: `/probe run &lt;job&gt;`"
                }
            }

            `when`("its context throws an unexpected exception") {
                val (output, markdown) =
                    replyOf(
                        command =
                            ProbeCommand(
                                commandData = slashData("run", "nightly"),
                                failure = IllegalStateException("database down"),
                            ),
                    )

                then("the requester gets the short generic reply, and the output keeps the failure as its reason") {
                    output.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                    output.errorReason shouldBe IllegalStateException("database down").toString()
                    markdown shouldBe "Something went wrong handling `/probe`. Please try again."
                }
            }

            `when`("a command without a name throws") {
                val (_, markdown) =
                    replyOf(
                        command =
                            TestCommand(
                                idempotencyKey = UUID.randomUUID(),
                                commandData = slashData(),
                                failure = IllegalStateException("boom"),
                            ),
                    )

                then("the generic reply names no command") {
                    markdown shouldBe "Something went wrong handling that command. Please try again."
                }
            }
        }

        given("a mention command whose context throws") {
            val command =
                TestCommand(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createMentionInboundCommand(),
                    failure = IllegalStateException("boom"),
                )

            `when`("handleEvent catches it") {
                val output = command.handleEvent()

                then("it fails as before and stages no reply, since only slash commands answer this way") {
                    output.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                    command.drainIntents().shouldBeEmpty()
                }
            }
        }
    })

private enum class ProbeSubCommand(
    override val subCommandIdentifier: String,
    override val usage: String,
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
) : SubCommandDefinition {
    NONE(subCommandIdentifier = "", usage = "/probe"),
    RUN(subCommandIdentifier = "run", usage = "/probe run <job>", requiresArguments = true, minRequiredArgs = 1),
}

private class ProbeCommand(
    commandData: InboundCommand,
    private val failure: RuntimeException = IllegalStateException("the probe context is never built"),
) : Command<ProbeSubCommand>(
        idempotencyKey = UUID.randomUUID(),
        commandData = commandData,
    ) {
    override val slashCommandName: String = "/probe"

    override val subCommandDefinitions: List<ProbeSubCommand> = ProbeSubCommand.entries

    override fun parseContext(subCommand: SubCommand<ProbeSubCommand>): CommandContext<out ProbeSubCommand> =
        throw failure

    override fun findSubCommandDefinition(): ProbeSubCommand {
        val identifier = commandData.subCommands.firstOrNull() ?: return ProbeSubCommand.NONE
        return findSubCommandByIdentifier<ProbeSubCommand>(identifier = identifier)
            ?: throw SubCommandParseException(
                commandName = "ProbeCommand",
                subCommandName = identifier,
                errorCode = CommandErrorCode.SUBCOMMAND_NOT_FOUND,
                details = emptyList(),
            )
    }
}
