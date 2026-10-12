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
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.standup.createStandupAnswerCandidate
import dev.notypie.domain.standup.dto.StandupAnswerCandidate
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StandupSlashContextTest :
    BehaviorSpec({

        fun contextFor(
            definition: StandupSubCommandDefinition,
            options: List<String> = emptyList(),
            answerCandidates: List<StandupAnswerCandidate> = emptyList(),
            intentQueue: IntentQueue,
        ) = StandupSlashContext(
            commandBasicInfo = createCommandBasicInfo(),
            triggerHandle = "trigger-standup",
            answerCandidates = answerCandidates,
            subCommand = SubCommand.of(definition = definition, options = options),
            intents = intentQueue,
        )

        fun requesterEphemeralMarkdown(effects: List<CommandEffect>): String {
            val ephemeral = effects.single().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
            ephemeral.target.id shouldBe TEST_CHANNEL_ID
            ephemeral.recipient shouldBe UserRef(id = TEST_USER_ID)
            return ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown
        }

        val daily = createStandupAnswerCandidate(routineName = "Daily Standup")
        val dailyTwin = createStandupAnswerCandidate(routineName = "daily  standup")
        val retro = createStandupAnswerCandidate(routineName = "Retro & Planning")
        val release = createStandupAnswerCandidate(routineName = "Release `v2`")

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
                        "Usage: `/standup stop &lt;routine-name&gt;`"
                }
            }
        }

        given("the ANSWER sub-command when no standup is collecting the requester's answer here") {
            val intentQueue = createIntentQueue()
            val context = contextFor(definition = StandupSubCommandDefinition.ANSWER, intentQueue = intentQueue)

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the requester alone is told so, and the command still succeeds") {
                    result.ok shouldBe true
                    requesterEphemeralMarkdown(effects = effects) shouldBe
                        "No standup is collecting your answer in this channel right now."
                }
            }
        }

        given("the ANSWER sub-command with one collecting standup and no routine name") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    answerCandidates = listOf(daily),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("its answer modal opens on the slash trigger with a channel-only origin notice") {
                    result.ok shouldBe true
                    val open = effects.single().shouldBeInstanceOf<OutboundMessage.OpenModal>()
                    open.handle.raw shouldBe "trigger-standup"
                    open.form shouldBe
                        ModalForm.StandupFill(
                            sessionUid = daily.sessionUid,
                            routineUid = daily.routineUid,
                            requesterId = TEST_USER_ID,
                            originNotice =
                                MessageRef(conversation = ConversationTarget(id = TEST_CHANNEL_ID), messageId = ""),
                        )
                }
            }
        }

        given("the ANSWER sub-command with several collecting standups and no routine name") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    answerCandidates = listOf(daily, retro, release),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the command fails and lists the open names, escaped and with backticks kept out of the spans") {
                    result.ok shouldBe false
                    requesterEphemeralMarkdown(effects = effects) shouldBe
                        "Several standups are open: `Daily Standup`, `Retro &amp; Planning`, `Release 'v2'`. " +
                        "Use `/standup answer &lt;routine-name&gt;`."
                }
            }
        }

        given("the ANSWER sub-command with two collecting standups of the same name and no routine name") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    answerCandidates = listOf(daily, dailyTwin),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("no modal opens and the requester is sent to the DM button, since a name cannot tell them apart") {
                    result.ok shouldBe false
                    requesterEphemeralMarkdown(effects = effects) shouldBe
                        "Several standups named `Daily Standup` are open here; " +
                        "use the *Fill in standup* button in your DM."
                }
            }
        }

        given("the ANSWER sub-command naming a routine that two collecting standups share") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    options = listOf("DAILY", "standup"),
                    answerCandidates = listOf(retro, daily, dailyTwin),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the first match is not opened; the requester is sent to the DM button instead") {
                    result.ok shouldBe false
                    requesterEphemeralMarkdown(effects = effects) shouldBe
                        "Several standups named `Daily Standup` are open here; " +
                        "use the *Fill in standup* button in your DM."
                }
            }
        }

        given("the ANSWER sub-command with no name while a duplicate name sits beside another routine") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    answerCandidates = listOf(daily, dailyTwin, retro),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("each distinct name is listed once") {
                    result.ok shouldBe false
                    requesterEphemeralMarkdown(effects = effects) shouldBe
                        "Several standups are open: `Daily Standup`, `Retro &amp; Planning`. " +
                        "Use `/standup answer &lt;routine-name&gt;`."
                }
            }
        }

        given("the ANSWER sub-command naming one of several collecting standups") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    options = listOf("  retro", "", "&", "PLANNING "),
                    answerCandidates = listOf(daily, retro),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the normalized name matches ignoring case and only that routine's modal opens") {
                    result.ok shouldBe true
                    val form =
                        effects
                            .single()
                            .shouldBeInstanceOf<OutboundMessage.OpenModal>()
                            .form
                            .shouldBeInstanceOf<ModalForm.StandupFill>()
                    form.sessionUid shouldBe retro.sessionUid
                    form.routineUid shouldBe retro.routineUid
                }
            }
        }

        given("the ANSWER sub-command naming a standup that is not collecting here") {
            val intentQueue = createIntentQueue()
            val context =
                contextFor(
                    definition = StandupSubCommandDefinition.ANSWER,
                    options = listOf("Weekly", "<sync>"),
                    answerCandidates = listOf(daily),
                    intentQueue = intentQueue,
                )

            `when`("runCommand is invoked") {
                val result = context.runCommand()
                val effects = intentQueue.drainSnapshot()

                then("the command fails, no modal opens, and the echoed name is escaped") {
                    result.ok shouldBe false
                    requesterEphemeralMarkdown(effects = effects) shouldBe
                        "No standup named `Weekly &lt;sync&gt;` is collecting your answer here."
                }
            }
        }
    })
