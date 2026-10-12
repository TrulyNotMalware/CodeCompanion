package dev.notypie.domain.command.entity

import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.entity.context.form.StandupSlashContext
import dev.notypie.domain.command.entity.slash.StandupCommand
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.standup.createStandupAnswerCandidate
import dev.notypie.domain.standup.dto.StandupAnswerCandidate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class StandupCommandTest :
    BehaviorSpec({

        fun standupCommand(subCommands: List<String>, answerCandidates: List<StandupAnswerCandidate> = emptyList()) =
            StandupCommand(
                idempotencyKey = UUID.randomUUID(),
                commandData = createSlashInboundCommand(subCommands = subCommands, triggerId = "trigger-standup"),
                answerCandidates = answerCandidates,
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

            `when`("subcommand is 'answer'") {
                val definition = standupCommand(subCommands = listOf("answer")).findSubCommandDefinition()

                then("should return StandupSubCommandDefinition.ANSWER") {
                    definition shouldBe StandupSubCommandDefinition.ANSWER
                }
            }

            `when`("subcommand is unknown") {
                val command = standupCommand(subCommands = listOf("unknown_sub"))

                then("should throw SubCommandParseException") {
                    shouldThrow<SubCommandParseException> {
                        command.findSubCommandDefinition()
                    }
                }

                then("handleEvent answers the requester with the unknown name and /standup's escaped usage lines") {
                    val output = command.handleEvent()
                    output.ok shouldBe false
                    output.commandDetailType shouldBe CommandDetailType.ERROR_RESPONSE
                    command
                        .drainIntents()
                        .single()
                        .shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe
                        "Unknown subcommand `unknown_sub`.\nUsage:\n• `/standup setup`\n• `/standup list`\n" +
                        "• `/standup stop &lt;routine-name&gt;`\n• `/standup answer [routine-name]`"
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

        given("StandupCommand handleEvent with 'answer' and one preloaded candidate") {
            val candidate = createStandupAnswerCandidate()
            val command = standupCommand(subCommands = listOf("answer"), answerCandidates = listOf(candidate))

            `when`("handleEvent") {
                val result = command.handleEvent()
                val effects = command.drainIntents()

                then("the candidate reaches the context and its answer modal opens on the slash trigger") {
                    result.ok shouldBe true
                    val open = effects.single().shouldBeInstanceOf<OutboundMessage.OpenModal>()
                    open.handle.raw shouldBe "trigger-standup"
                    val form = open.form.shouldBeInstanceOf<ModalForm.StandupFill>()
                    form.sessionUid shouldBe candidate.sessionUid
                    form.routineUid shouldBe candidate.routineUid
                }
            }
        }

        given("StandupCommand handleEvent with 'answer' built without candidates") {
            val command = standupCommand(subCommands = listOf("answer"))

            `when`("handleEvent") {
                val result = command.handleEvent()
                val effects = command.drainIntents()

                then("the requester hears that nothing is collecting and no modal opens") {
                    result.ok shouldBe true
                    val ephemeral = effects.single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        StandupSlashContext.NO_COLLECTING_STANDUP_MESSAGE
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
