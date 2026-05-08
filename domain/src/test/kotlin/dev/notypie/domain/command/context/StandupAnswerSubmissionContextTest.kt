package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID

class StandupAnswerSubmissionContextTest :
    BehaviorSpec({
        given("StandupAnswerSubmissionContext receives a valid view_submission") {
            val sessionUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                StandupAnswerSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    // Inputs intentionally arrive in reverse block-id order to prove the
                    // context sorts by `standup_q_<index>` rather than relying on iteration
                    // order from Slack's `view.state.values` map.
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "Work on #13",
                                blockId = "standup_q_1",
                            ),
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "Finished #12",
                                blockId = "standup_q_0",
                            ),
                        ),
                    idempotencyKey = sessionUid,
                ).copy(
                    routingExtras = listOf("U_STANDUP", "D_NOTICE", "1700000000.000300"),
                    privateMetadata =
                        "$sessionUid,${CommandDetailType.STANDUP_ANSWER_SUBMIT.name}," +
                            "U_STANDUP,D_NOTICE,1700000000.000300",
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                }

                then("RecordStandupAnswer carries the responses in modal order") {
                    val record = intents.filterIsInstance<CommandIntent.RecordStandupAnswer>().single()
                    record.sessionUid shouldBe sessionUid
                    record.userId shouldBe "U_STANDUP"
                    record.responses shouldBe listOf("Finished #12", "Work on #13")
                }

                then("UpdateNoticeMessage collapses the originating DM") {
                    val update = intents.filterIsInstance<CommandIntent.UpdateNoticeMessage>().single()
                    update.channel shouldBe "D_NOTICE"
                    update.messageTs shouldBe "1700000000.000300"
                    update.commandDetailType shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                }
            }
        }

        given("StandupAnswerSubmissionContext receives malformed session metadata") {
            val intentQueue = createIntentQueue()
            val context =
                StandupAnswerSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(idempotencyKey = "not-a-uuid")

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
