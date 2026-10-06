package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createInteractionResponseInboundCommand
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.ReactionContext
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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
    })
