package dev.notypie.domain.command.entity

import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.entity.context.form.StandupSlashContext
import dev.notypie.domain.command.entity.slash.StandupCommand
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class StandupCommandTest :
    BehaviorSpec({

        fun standupCommand(subCommands: List<String>) =
            StandupCommand(
                idempotencyKey = UUID.randomUUID(),
                commandData = createSlashInboundCommand(subCommands = subCommands, triggerId = "trigger-standup"),
            )

        given("StandupCommand findSubCommandDefinition") {
            `when`("no subcommands provided") {
                val definition = standupCommand(subCommands = emptyList()).findSubCommandDefinition()

                then("should return StandupSubCommandDefinition.NONE") {
                    definition shouldBe StandupSubCommandDefinition.NONE
                }
            }

            `when`("subcommand is 'setup'") {
                val definition = standupCommand(subCommands = listOf("setup")).findSubCommandDefinition()

                then("should return StandupSubCommandDefinition.SETUP") {
                    definition shouldBe StandupSubCommandDefinition.SETUP
                }
            }

            `when`("subcommand is 'list'") {
                val definition = standupCommand(subCommands = listOf("list")).findSubCommandDefinition()

                then("should return StandupSubCommandDefinition.LIST") {
                    definition shouldBe StandupSubCommandDefinition.LIST
                }
            }

            `when`("subcommand is 'stop'") {
                val definition = standupCommand(subCommands = listOf("stop", "daily")).findSubCommandDefinition()

                then("should return StandupSubCommandDefinition.STOP") {
                    definition shouldBe StandupSubCommandDefinition.STOP
                }
            }

            `when`("subcommand is unknown") {
                val command = standupCommand(subCommands = listOf("unknown_sub"))

                then("should throw SubCommandParseException") {
                    shouldThrow<SubCommandParseException> {
                        command.findSubCommandDefinition()
                    }
                }
            }
        }

        given("StandupCommand handleEvent with a bare 'stop'") {
            val command = standupCommand(subCommands = listOf("stop"))

            `when`("handleEvent") {
                val result = command.handleEvent()
                val effects = command.drainIntents()

                then("the sub-command passes validation and the requester gets the usage reply") {
                    result.ok shouldBe false
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_SETUP_REQUEST
                    val ephemeral = effects.single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        StandupSlashContext.STOP_USAGE_MESSAGE
                }
            }
        }

        given("StandupCommand handleEvent with 'stop daily sync'") {
            val command = standupCommand(subCommands = listOf("stop", "daily", "sync"))

            `when`("handleEvent") {
                val result = command.handleEvent()
                val effects = command.drainIntents()

                then("a StopStandupRoutine intent carries the space-joined routine name") {
                    result.ok shouldBe true
                    effects.single() shouldBe CommandIntent.StopStandupRoutine(routineName = "daily sync")
                }
            }
        }

        given("StandupCommand handleEvent with 'list'") {
            val command = standupCommand(subCommands = listOf("list"))

            `when`("handleEvent") {
                val result = command.handleEvent()
                val effects = command.drainIntents()

                then("a ListStandupRoutines intent is queued and no modal opens") {
                    result.ok shouldBe true
                    effects.single() shouldBe CommandIntent.ListStandupRoutines
                }
            }
        }
    })
