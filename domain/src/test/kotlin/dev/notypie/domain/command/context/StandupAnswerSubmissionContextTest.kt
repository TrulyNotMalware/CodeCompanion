package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inboundField
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    action = approveAction(isSelected = true),
                    // Inputs intentionally arrive in reverse block-id order to prove the
                    // context sorts by `standup_q_<index>` rather than relying on iteration
                    // order from Slack's `view.state.values` map.
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "Work on #13",
                                key = "standup_q_1",
                            ),
                            inboundField(
                                kind = InboundFieldKind.TEXT,
                                isSelected = true,
                                rawValue = "Finished #12",
                                key = "standup_q_0",
                            ),
                        ),
                    idempotencyKey = sessionUid,
                    routingExtras = listOf("U_STANDUP", "D_NOTICE", "1700000000.000300"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
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

                then("UpdateMessage collapses the originating DM") {
                    val update = intents.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe "D_NOTICE"
                    update.ref.messageId shouldBe "1700000000.000300"
                    update.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe "Standup submitted."
                    update.detailType shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
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
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(idempotencyKey = "not-a-uuid")

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
