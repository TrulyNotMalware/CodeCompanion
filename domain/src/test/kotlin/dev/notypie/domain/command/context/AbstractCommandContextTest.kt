package dev.notypie.domain.command.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.dto.isEmpty
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AbstractCommandContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}
        val eventQueue = createDomainEventQueue()

        given("Not implemented abstract command context") {
            val abstractCommandContext =
                object : CommandContext<NoSubCommands>(
                    commandBasicInfo = createCommandBasicInfo(),
                    requestHeaders = SlackRequestHeaders(),
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    subCommand = SubCommand.empty(),
                ) {
                    override fun parseCommandType(): CommandType = CommandType.SIMPLE

                    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTHING
                }

            `when`("runCommand") {
                val res = abstractCommandContext.runCommand()
                then("should return CommandOutput.empty") {
                    res.isEmpty() shouldBe true
                }
            }
        }
        /**
         * Contract Test: Ensures runCommand() remain overridable.
         * Protects against accidental removal of 'open' keyword during refactoring.
         */
        given("Override runCommand function") {
            val runCommandReturnValue = CommandOutput.empty()
            val overrideContext =
                object : CommandContext<NoSubCommands>(
                    commandBasicInfo = createCommandBasicInfo(),
                    requestHeaders = SlackRequestHeaders(),
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    subCommand = SubCommand.empty(),
                ) {
                    override fun parseCommandType(): CommandType = CommandType.SIMPLE

                    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTHING

                    override fun runCommand(): CommandOutput = runCommandReturnValue
                }
            `when`("runCommand") {
                val res = overrideContext.runCommand()
                then("should return override value") {
                    res shouldBe runCommandReturnValue
                }
            }
        }
    })

class AbstractReactionCommandContextTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}
        val eventQueue = createDomainEventQueue()

        given("Not implemented abstract reaction context") {
            val runCommandReturnValue = CommandOutput.empty()
            val handleInteractionReturnValue = CommandOutput.empty()
            val reactionContext =
                object : ReactionContext<NoSubCommands>(
                    slackEventBuilder = eventBuilder,
                    requestHeaders = SlackRequestHeaders(),
                    commandBasicInfo = createCommandBasicInfo(),
                    events = eventQueue,
                    subCommand = SubCommand.empty(),
                ) {
                    override fun parseCommandType(): CommandType = CommandType.SIMPLE

                    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTHING

                    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput =
                        handleInteractionReturnValue

                    override fun runCommand(): CommandOutput = runCommandReturnValue
                }
            `when`("runCommand") {
                val res = reactionContext.runCommand()
                then("should return override value") {
                    res shouldBe runCommandReturnValue
                }
            }
            `when`("handleInteraction") {
                val res =
                    reactionContext.handleInteraction(
                        interactionPayload = createInteractionPayloadInput(),
                    )
                then("should return override value") {
                    res shouldBe handleInteractionReturnValue
                }
            }
        }
    })
