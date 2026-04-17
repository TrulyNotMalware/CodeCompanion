package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CommandTest :
    BehaviorSpec({

        given("Command.handleEvent with non-interaction command") {
            val commandData = createAppMentionSlackCommandData()
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
                            requestHeaders = SlackRequestHeaders(),
                            intents = intents,
                        )

                    override fun findSubCommandDefinition(): NoSubCommands = NoSubCommands()
                }

            `when`("handleEvent succeeds") {
                val result = command.handleEvent()

                then("should return CommandOutput from context.runCommand") {
                    result.ok shouldBe false // EmptyContext returns CommandOutput.empty()
                }
            }
        }

        given("Command.handleEvent with interaction command") {
            val idempotencyKey = UUID.randomUUID()
            val interactionPayload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.APPROVAL_FORM,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = idempotencyKey,
                )
            val commandData =
                SlackCommandData(
                    appId = "A_TEST",
                    appToken = "TOKEN",
                    publisherId = "U_TEST",
                    publisherName = "tester",
                    channel = "C_TEST",
                    channelName = "general",
                    slackCommandType = SlackCommandType.INTERACTION_RESPONSE,
                    rawHeader = SlackRequestHeaders(),
                    rawBody = emptyMap(),
                    body = interactionPayload,
                )

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

                                override fun parseCommandDetailType() = CommandDetailType.APPROVAL_FORM

                                override fun runCommand() = CommandOutput.empty()
                            }

                        override fun findSubCommandDefinition() = NoSubCommands()
                    }

                val result = command.handleEvent()

                then("should return success from handleInteraction") {
                    result.ok shouldBe true // default handleInteraction calls interactionSuccessResponse
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
                                requestHeaders = SlackRequestHeaders(),
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

        given("Command.handleEvent when executeCommand throws") {
            val commandData = createAppMentionSlackCommandData()
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
