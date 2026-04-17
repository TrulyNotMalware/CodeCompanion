package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.dto.isEmpty
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AbstractCommandContextTest :
    BehaviorSpec({
        val intentQueue = createIntentQueue()

        given("Not implemented abstract command context") {
            val abstractCommandContext =
                object : CommandContext<NoSubCommands>(
                    commandBasicInfo = createCommandBasicInfo(),
                    requestHeaders = SlackRequestHeaders(),
                    intents = intentQueue,
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

        // Contract Test: Ensures runCommand() remain overridable.
        // Protects against accidental removal of 'open' keyword during refactoring.
        given("Override runCommand function") {
            val runCommandReturnValue = CommandOutput.empty()
            val overrideContext =
                object : CommandContext<NoSubCommands>(
                    commandBasicInfo = createCommandBasicInfo(),
                    requestHeaders = SlackRequestHeaders(),
                    intents = intentQueue,
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
        val intentQueue = createIntentQueue()

        given("Not implemented abstract reaction context") {
            val runCommandReturnValue = CommandOutput.empty()
            val handleInteractionReturnValue = CommandOutput.empty()
            val reactionContext =
                object : ReactionContext<NoSubCommands>(
                    requestHeaders = SlackRequestHeaders(),
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
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

        given("ReactionContext interactionSuccessResponse") {
            val testCommandBasicInfo = createCommandBasicInfo()
            val testIntentQueue = createIntentQueue()

            val reactionContext =
                object : ReactionContext<NoSubCommands>(
                    requestHeaders = SlackRequestHeaders(),
                    commandBasicInfo = testCommandBasicInfo,
                    intents = testIntentQueue,
                    subCommand = SubCommand.empty(),
                ) {
                    override fun parseCommandType(): CommandType = CommandType.SIMPLE

                    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REPLACE_TEXT

                    fun callInteractionSuccessResponse(
                        responseUrl: String,
                        mkdMessage: String = "Successfully processed.",
                    ) = interactionSuccessResponse(
                        responseUrl = responseUrl,
                        mkdMessage = mkdMessage,
                    )

                    fun callInteractionSuccessResponseWithResults(
                        responseUrl: String,
                        mkdMessage: String = "Successfully processed.",
                        results: CommandOutput,
                    ) = interactionSuccessResponse(
                        responseUrl = responseUrl,
                        mkdMessage = mkdMessage,
                        results = results,
                    )
                }

            `when`("interactionSuccessResponse without results") {
                val result =
                    reactionContext.callInteractionSuccessResponse(
                        responseUrl = TEST_BASE_URL,
                    )

                then("should return success CommandOutput") {
                    result.ok shouldBe true
                }

                then("should add ReplaceMessage intent to the queue") {
                    val intents = testIntentQueue.drainSnapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                    val replaceIntent = intents.first() as CommandIntent.ReplaceMessage
                    replaceIntent.responseUrl shouldBe TEST_BASE_URL
                    replaceIntent.markdownText shouldBe "Successfully processed."
                }
            }

            `when`("interactionSuccessResponse with results") {
                val expectedResults =
                    CommandOutput(
                        ok = true,
                        apiAppId = testCommandBasicInfo.appId,
                        status = Status.DO_NOTHING,
                        channel = testCommandBasicInfo.channel,
                        commandType = CommandType.SIMPLE,
                        commandDetailType = CommandDetailType.NOTHING,
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
                        publisherId = testCommandBasicInfo.publisherId,
                    )
                val result =
                    reactionContext.callInteractionSuccessResponseWithResults(
                        responseUrl = TEST_BASE_URL,
                        results = expectedResults,
                    )

                then("should return the provided results instead of replace command output") {
                    result shouldBe expectedResults
                }

                then("should still add ReplaceMessage intent to the queue") {
                    val intents = testIntentQueue.drainSnapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                }
            }
        }
    })
