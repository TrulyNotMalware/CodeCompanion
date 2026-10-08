package dev.notypie.domain.command.entity

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.entity.slash.RequestMeetingCommand
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class RequestMeetingCommandTest :
    BehaviorSpec({

        given("RequestMeetingCommand findSubCommandDefinition") {
            `when`("no subcommands provided") {
                val commandData = createSlashInboundCommand()
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
                val commandData = createSlashInboundCommand(subCommands = listOf("list"))
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
                val commandData = createSlashInboundCommand(subCommands = listOf("unknown_sub"))
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
                val commandData = createSlashInboundCommand(subCommands = listOf("list", "today"))
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
                    intent.publisherId shouldBe TEST_USER_ID
                    ChronoUnit.DAYS.between(intent.startDate, intent.endDate) shouldBe 1L
                    intent.startDate shouldBe before.toLocalDate().atStartOfDay()
                }
            }

            `when`("subcommand text is 'list bogus'") {
                val commandData = createSlashInboundCommand(subCommands = listOf("list", "bogus"))
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = commandData,
                    )
                val result = command.handleEvent()
                val intents = command.drainIntents()

                then("should fail and emit Ephemeral outbound with Unknown range message") {
                    result.ok shouldBe false
                    intents.size shouldBe 1
                    val ephemeral = intents.first().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown
                        .contains("Unknown range 'bogus'") shouldBe true
                }
            }
        }

        given("RequestMeetingCommand handleEvent with CALENDAR sub command") {
            fun run(vararg tokens: String): Pair<CommandOutput, List<CommandEffect>> {
                val command =
                    RequestMeetingCommand(
                        idempotencyKey = UUID.randomUUID(),
                        commandData = createSlashInboundCommand(subCommands = listOf("calendar") + tokens),
                    )
                return command.handleEvent() to command.drainIntents()
            }

            fun List<CommandEffect>.usageEphemeral(): String =
                single()
                    .shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    .content
                    .shouldBeInstanceOf<MessageContent.Text>()
                    .markdown

            `when`("subcommand text is 'calendar connect'") {
                val (result, intents) = run("connect")

                then("should succeed and emit CalendarConnect for the requester") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    intents.single().shouldBeInstanceOf<CommandIntent.CalendarConnect>().userId shouldBe TEST_USER_ID
                }
            }

            `when`("subcommand text is 'calendar DISCONNECT' in upper case") {
                val (result, intents) = run("DISCONNECT")

                then("should match case-insensitively and emit CalendarDisconnect") {
                    result.ok shouldBe true
                    intents.single().shouldBeInstanceOf<CommandIntent.CalendarDisconnect>().userId shouldBe TEST_USER_ID
                }
            }

            `when`("subcommand text is 'calendar status'") {
                val (result, intents) = run("status")

                then("should emit CalendarStatus") {
                    result.ok shouldBe true
                    intents.single().shouldBeInstanceOf<CommandIntent.CalendarStatus>().userId shouldBe TEST_USER_ID
                }
            }

            `when`("subcommand text is 'calendar' without an action") {
                val (result, intents) = run()

                then("should fail with the usage line and emit no intent") {
                    result.ok shouldBe false
                    intents.usageEphemeral() shouldBe "Usage: ${MeetingSubCommandDefinition.CALENDAR.usage}"
                }
            }

            `when`("subcommand text is 'calendar bogus'") {
                val (result, intents) = run("bogus")

                then("should fail and name the unknown action") {
                    result.ok shouldBe false
                    intents.usageEphemeral() shouldBe
                        "Unknown action 'bogus'. Usage: ${MeetingSubCommandDefinition.CALENDAR.usage}"
                }
            }

            `when`("subcommand text is 'calendar connect now'") {
                val (result, intents) = run("connect", "now")

                then("should reject the extra argument with the usage line") {
                    result.ok shouldBe false
                    intents.usageEphemeral() shouldBe "Usage: ${MeetingSubCommandDefinition.CALENDAR.usage}"
                }
            }
        }

        given("RequestMeetingCommand handleEvent with no subcommands") {
            val idempotencyKey = UUID.randomUUID()
            val commandData = createSlashInboundCommand()

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
