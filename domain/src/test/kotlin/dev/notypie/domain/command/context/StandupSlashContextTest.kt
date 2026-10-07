package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.StandupSlashContext
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StandupSlashContextTest :
    BehaviorSpec({

        fun contextFor(
            definition: StandupSubCommandDefinition,
            options: List<String> = emptyList(),
            intentQueue: IntentQueue,
        ) = StandupSlashContext(
            commandBasicInfo = createCommandBasicInfo(),
            triggerHandle = "trigger-standup",
            subCommand = SubCommand.of(definition = definition, options = options),
            intents = intentQueue,
        )

        given("StandupSlashContext metadata") {
            val context = contextFor(definition = StandupSubCommandDefinition.LIST, intentQueue = createIntentQueue())

            `when`("commandType and commandDetailType are read") {
                then("they stay PIPELINE / STANDUP_SETUP_REQUEST for every sub-command") {
                    context.commandType shouldBe CommandType.PIPELINE
                    context.commandDetailType shouldBe CommandDetailType.STANDUP_SETUP_REQUEST
                }
            }
        }

        given("the SETUP sub-command") {
            val intentQueue = createIntentQueue()
            val context = contextFor(definition = StandupSubCommandDefinition.SETUP, intentQueue = intentQueue)

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the setup modal opens on the trigger for the requester and command channel") {
                    result.ok shouldBe true
                    val open = effects.single().shouldBeInstanceOf<OutboundMessage.OpenModal>()
                    open.handle.raw shouldBe "trigger-standup"
                    val form = open.form.shouldBeInstanceOf<ModalForm.StandupSetup>()
                    form.creatorId shouldBe TEST_USER_ID
                    form.commandChannel.id shouldBe TEST_CHANNEL_ID
                }
            }
        }

        given("the LIST sub-command") {
            val intentQueue = createIntentQueue()
            val context = contextFor(definition = StandupSubCommandDefinition.LIST, intentQueue = intentQueue)

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("only a ListStandupRoutines intent is queued") {
                    result.ok shouldBe true
                    effects.single() shouldBe CommandIntent.ListStandupRoutines
                }
            }
        }

        given("the STOP sub-command with a two-word routine name") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.STOP,
                    options = listOf("Daily", "", "Sync"),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("blank tokens are dropped and the name is space-joined") {
                    result.ok shouldBe true
                    effects.single() shouldBe CommandIntent.StopStandupRoutine(routineName = "Daily Sync")
                }
            }
        }

        given("the STOP sub-command with options carrying surrounding and inner whitespace") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.STOP,
                    options = listOf("  Daily ", "Sync\t"),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the name is normalized the way the setup service stores it") {
                    effects.single() shouldBe CommandIntent.StopStandupRoutine(routineName = "Daily Sync")
                }
            }
        }

        given("the STOP sub-command with a blank routine name") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.STOP,
                    options = listOf(" ", ""),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the command fails with an ephemeral usage reply to the requester and no intent") {
                    result.ok shouldBe false
                    val ephemeral = effects.single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.target.id shouldBe TEST_CHANNEL_ID
                    ephemeral.recipient shouldBe UserRef(id = TEST_USER_ID)
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        "Usage: `/standup stop <routine-name>`"
                }
            }
        }
    })
