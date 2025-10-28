package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
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
                object : CommandContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    requestHeaders = SlackRequestHeaders(),
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                ) {
                    override fun parseCommandType(): CommandType = CommandType.SIMPLE

                    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTHING
                }

            `when`("runCommand") {
                val res = abstractCommandContext.runCommand()
                val resWithDetailType =
                    abstractCommandContext.runCommand(commandDetailType = CommandDetailType.NOTHING)
                then("should return CommandOutput.empty") {
                    res.isEmpty() shouldBe true
                    resWithDetailType.isEmpty() shouldBe true
                }
            }

            `when`("handleInteraction") {
                val res =
                    abstractCommandContext.handleInteraction(
                        interactionPayload = createInteractionPayloadInput(),
                    )
                then("should return CommandOutput.empty") {
                    res.isEmpty() shouldBe true
                }
            }
        }
        /**
         * Contract Test: Ensures runCommand() and handleInteraction() remain overridable.
         * Protects against accidental removal of 'open' keyword during refactoring.
         */
        given("Override runCommand and handleInteraction function") {
            val runCommandReturnValue = CommandOutput.empty()
            val handleInteractionReturnValue = CommandOutput.empty()
            val overrideContext =
                object : CommandContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    requestHeaders = SlackRequestHeaders(),
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                ) {
                    override fun parseCommandType(): CommandType = CommandType.SIMPLE

                    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTHING

                    override fun runCommand(): CommandOutput = runCommandReturnValue

                    override fun runCommand(commandDetailType: CommandDetailType) = runCommandReturnValue

                    override fun handleInteraction(interactionPayload: InteractionPayload) =
                        handleInteractionReturnValue
                }
            `when`("runCommand") {
                val res = overrideContext.runCommand()
                val resWithDetailType =
                    overrideContext.runCommand(commandDetailType = CommandDetailType.NOTHING)
                then("should return override value") {
                    res shouldBe runCommandReturnValue
                    resWithDetailType shouldBe runCommandReturnValue
                }
            }

            `when`("handleInteraction") {
                val res =
                    overrideContext.handleInteraction(
                        interactionPayload = createInteractionPayloadInput(),
                    )
                then("should return override value") {
                    res shouldBe handleInteractionReturnValue
                }
            }
        }
    })
