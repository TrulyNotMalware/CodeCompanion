package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class RequestMeetingCommandTest :
    BehaviorSpec({

        fun createMeetingCommandData(subCommands: List<String> = emptyList()) =
            SlackCommandData(
                appId = "A_TEST",
                appToken = "TOKEN",
                publisherId = "U_TEST",
                publisherName = "tester",
                channel = "C_TEST",
                channelName = "general",
                slackCommandType = SlackCommandType.SLASH,
                subCommands = subCommands,
                rawHeader = SlackRequestHeaders(),
                rawBody = emptyMap(),
                body = "",
            )

        given("RequestMeetingCommand findSubCommandDefinition") {
            `when`("no subcommands provided") {
                val commandData = createMeetingCommandData()
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = commandData,
                    )

                val definition = command.findSubCommandDefinition()

                then("should return MeetingSubCommandDefinition.NONE") {
                    definition shouldBe MeetingSubCommandDefinition.NONE
                }
            }

            `when`("subcommand is 'list'") {
                val commandData = createMeetingCommandData(subCommands = listOf("list"))
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = commandData,
                    )

                val definition = command.findSubCommandDefinition()

                then("should return MeetingSubCommandDefinition.LIST") {
                    definition shouldBe MeetingSubCommandDefinition.LIST
                }
            }

            `when`("subcommand is unknown") {
                val commandData = createMeetingCommandData(subCommands = listOf("unknown_sub"))
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = commandData,
                    )

                then("should throw SubCommandParseException") {
                    shouldThrow<SubCommandParseException> {
                        command.findSubCommandDefinition()
                    }
                }
            }
        }

        given("RequestMeetingCommand handleEvent with LIST sub command and range option") {
            `when`("subcommand text is 'list today'") {
                val commandData = createMeetingCommandData(subCommands = listOf("list", "today"))
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = commandData,
                    )
                val before = LocalDateTime.now()
                val result = command.handleEvent()
                val intents = command.drainIntents()

                then("should succeed and emit MeetingListRequest intent scoped to TODAY range") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    intents.size shouldBe 1
                    val intent = intents.first().shouldBeInstanceOf<CommandIntent.MeetingListRequest>()
                    intent.publisherId shouldBe "U_TEST"
                    // TODAY range spans exactly one day starting at start-of-day
                    ChronoUnit.DAYS.between(intent.startDate, intent.endDate) shouldBe 1L
                    intent.startDate shouldBe before.toLocalDate().atStartOfDay()
                }
            }

            `when`("subcommand text is 'list bogus'") {
                val commandData = createMeetingCommandData(subCommands = listOf("list", "bogus"))
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = commandData,
                    )
                val result = command.handleEvent()
                val intents = command.drainIntents()

                then("should fail and emit EphemeralResponse with Unknown range message") {
                    result.ok shouldBe false
                    intents.size shouldBe 1
                    val intent = intents.first().shouldBeInstanceOf<CommandIntent.EphemeralResponse>()
                    intent.message.contains("Unknown range 'bogus'") shouldBe true
                }
            }
        }

        given("RequestMeetingCommand handleEvent with INTERACTION_RESPONSE") {
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
                    slackCommandType = SlackCommandType.SLASH,
                    rawHeader = SlackRequestHeaders(),
                    rawBody = emptyMap(),
                    body = interactionPayload,
                )

            val command =
                RequestMeetingCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                )

            `when`("handleEvent") {
                val result = command.handleEvent()

                then("should return success") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }
            }
        }
    })
