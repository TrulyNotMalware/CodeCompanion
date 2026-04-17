package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createEventCallbackData
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.createRichTextBlock
import dev.notypie.domain.command.createSlackEventCallBackRequest
import dev.notypie.domain.command.createTextElement
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class InteractionCommandTest :
    BehaviorSpec({

        given("InteractionCommand with EVENT_CALLBACK (app_mention)") {
            val body =
                createSlackEventCallBackRequest(
                    event =
                        createEventCallbackData(
                            blocks =
                                listOf(
                                    createRichTextBlock(
                                        createTextElement(text = " notice hello"),
                                    ),
                                ),
                        ),
                )
            val commandData =
                createAppMentionSlackCommandData(body = body).copy(
                    slackCommandType = SlackCommandType.EVENT_CALLBACK,
                )
            val idempotencyKey = UUID.randomUUID()

            val command =
                InteractionCommand(
                    appName = "TestApp",
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                )

            `when`("handleEvent") {
                val result = command.handleEvent()

                then("should succeed") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }
            }
        }

        given("InteractionCommand with INTERACTION_RESPONSE (ReactionContext)") {
            val idempotencyKey = UUID.randomUUID()
            val interactionPayload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.NOTICE_FORM,
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

            val command =
                InteractionCommand(
                    appName = "TestApp",
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                )

            `when`("handleEvent") {
                val result = command.handleEvent()

                then("should succeed") {
                    result.ok shouldBe true
                }
            }
        }

        given("InteractionCommand with INTERACTION_RESPONSE (non-ReactionContext)") {
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

            val command =
                InteractionCommand(
                    appName = "TestApp",
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                )

            `when`("handleEvent") {
                val result = command.handleEvent()

                then("should fail because APPROVAL_FORM creates non-ReactionContext") {
                    result.ok shouldBe false
                    result.status shouldBe Status.FAILED
                    result.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                }
            }
        }

        given("InteractionCommand findSubCommandDefinition") {
            `when`("body is InteractionPayload with REQUEST_MEETING_FORM") {
                val idempotencyKey = UUID.randomUUID()
                val interactionPayload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
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

                val command =
                    InteractionCommand(
                        appName = "TestApp",
                        idempotencyKey = idempotencyKey,
                        commandData = commandData,
                    )

                val definition = command.findSubCommandDefinition()

                then("should return MeetingSubCommandDefinition.NONE") {
                    definition.shouldBeInstanceOf<MeetingSubCommandDefinition>()
                    definition shouldBe MeetingSubCommandDefinition.NONE
                }
            }

            `when`("body is not InteractionPayload") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(createTextElement(text = " notice")),
                                    ),
                            ),
                    )
                val commandData =
                    createAppMentionSlackCommandData(body = body).copy(
                        slackCommandType = SlackCommandType.EVENT_CALLBACK,
                    )
                val idempotencyKey = UUID.randomUUID()

                val command =
                    InteractionCommand(
                        appName = "TestApp",
                        idempotencyKey = idempotencyKey,
                        commandData = commandData,
                    )

                val definition = command.findSubCommandDefinition()

                then("should return NoSubCommands") {
                    definition.shouldBeInstanceOf<NoSubCommands>()
                }
            }
        }
    })
