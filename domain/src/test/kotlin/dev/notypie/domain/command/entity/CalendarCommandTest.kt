package dev.notypie.domain.command.entity

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.slash.CALENDAR_USAGE
import dev.notypie.domain.command.entity.slash.CalendarCommand
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class CalendarCommandTest :
    BehaviorSpec({
        fun run(vararg tokens: String): Pair<CommandOutput, List<CommandEffect>> {
            val command =
                CalendarCommand(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createSlashInboundCommand(subCommands = tokens.toList()),
                )
            return command.handleEvent() to command.drainIntents()
        }

        fun List<CommandEffect>.usageEphemeral(): String {
            val ephemeral = single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
            ephemeral.recipient shouldBe null
            return ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown
        }

        given("the /calendar usage line") {
            then("names the command and its three actions") {
                CALENDAR_USAGE shouldBe "/calendar connect | disconnect | status"
            }
        }

        given("/calendar with one action") {
            `when`("the action is 'connect'") {
                val (result, intents) = run("connect")

                then("it succeeds as a CALENDAR_CONNECTION command and emits CalendarConnect for the requester") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    result.commandDetailType shouldBe CommandDetailType.CALENDAR_CONNECTION
                    intents.single().shouldBeInstanceOf<CommandIntent.CalendarConnect>().userId shouldBe TEST_USER_ID
                }
            }

            `when`("the action is 'DISCONNECT' in upper case") {
                val (result, intents) = run("DISCONNECT")

                then("it matches case-insensitively and emits CalendarDisconnect") {
                    result.ok shouldBe true
                    intents.single().shouldBeInstanceOf<CommandIntent.CalendarDisconnect>().userId shouldBe TEST_USER_ID
                }
            }

            `when`("the action is 'status' surrounded by blank tokens") {
                val (result, intents) = run("", "status", " ")

                then("the blanks are ignored and it emits CalendarStatus") {
                    result.ok shouldBe true
                    intents.single().shouldBeInstanceOf<CommandIntent.CalendarStatus>().userId shouldBe TEST_USER_ID
                }
            }
        }

        given("/calendar that does not name exactly one known action") {
            `when`("no action is given") {
                val (result, intents) = run()

                then("it fails with the usage line as a requester-only ephemeral and emits no intent") {
                    result.ok shouldBe false
                    intents.usageEphemeral() shouldBe "Usage: $CALENDAR_USAGE"
                }
            }

            `when`("the action is a typo") {
                val (result, intents) = run("conect")

                then("it fails and names the unknown action before the usage line") {
                    result.ok shouldBe false
                    intents.usageEphemeral() shouldBe "Unknown action 'conect'. Usage: $CALENDAR_USAGE"
                }
            }

            `when`("an extra argument follows the action") {
                val (result, intents) = run("connect", "now")

                then("it rejects the extra argument with the usage line") {
                    result.ok shouldBe false
                    intents.usageEphemeral() shouldBe "Usage: $CALENDAR_USAGE"
                }
            }
        }
    })
