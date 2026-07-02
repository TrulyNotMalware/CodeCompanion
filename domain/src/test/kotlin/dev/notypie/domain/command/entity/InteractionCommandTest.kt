package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createInteractionResponseInboundCommand
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class InteractionCommandTest :
    BehaviorSpec({

        given("InteractionCommand with a MENTION payload (app_mention)") {
            val commandData = createMentionInboundCommand(commandTokens = listOf("notice", "hello"))
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
                createInboundInteraction(
                    detailType = CommandDetailType.APPROVAL_CALLBACK,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = idempotencyKey,
                )
            val commandData = createInteractionResponseInboundCommand(interaction = interactionPayload)

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
                createInboundInteraction(
                    detailType = CommandDetailType.APPROVAL_REQUEST,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = idempotencyKey,
                )
            val commandData = createInteractionResponseInboundCommand(interaction = interactionPayload)

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
            `when`("payload is InboundInteraction with REQUEST_MEETING_FORM") {
                val idempotencyKey = UUID.randomUUID()
                val interactionPayload =
                    createInboundInteraction(
                        detailType = CommandDetailType.MEETING_CREATE_REQUEST,
                        action = approveAction(isSelected = true),
                        form = emptyList(),
                        idempotencyKey = idempotencyKey,
                    )
                val commandData = createInteractionResponseInboundCommand(interaction = interactionPayload)

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

            `when`("payload is not InboundInteraction") {
                val commandData = createMentionInboundCommand(commandTokens = listOf("notice"))
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
