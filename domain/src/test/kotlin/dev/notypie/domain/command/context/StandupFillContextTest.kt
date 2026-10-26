package dev.notypie.domain.command.context

import dev.notypie.domain.command.applyButtonField
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupFillContext
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.inbound.TriggerHandle
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.StandupAnswerConfirmation
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.rejectAction
import dev.notypie.domain.command.rejectButtonField
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class StandupFillContextTest :
    BehaviorSpec({
        given("StandupFillContext receives a Fill button click") {
            val sessionUid = UUID.randomUUID()
            val routineUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                StandupFillContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_PROMPT,
                    action = approveAction(isSelected = true),
                    form = listOf(applyButtonField()),
                    idempotencyKey = UUID.randomUUID(),
                    trigger = TriggerHandle(raw = "trigger-standup"),
                    message = MessageHandle(raw = "1700000000.000200"),
                    routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_PROMPT
                }

                then("an OpenModal effect carries routing and notice update context") {
                    val open = intents.filterIsInstance<OutboundMessage.OpenModal>().single()
                    open.handle.raw shouldBe "trigger-standup"
                    val form = open.form.shouldBeInstanceOf<ModalForm.StandupFill>()
                    form.sessionUid shouldBe sessionUid
                    form.routineUid shouldBe routineUid
                    form.requesterId shouldBe payload.actor.id
                    form.originNotice.conversation.id shouldBe payload.channelId
                    form.originNotice.messageId shouldBe "1700000000.000200"
                }

                then("nothing is recorded as a skip") {
                    intents.filterIsInstance<CommandIntent.RecordStandupSkip>().shouldBeEmpty()
                }
            }
        }

        given("StandupFillContext receives a Skip button click") {
            val sessionUid = UUID.randomUUID()
            val routineUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                StandupFillContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_PROMPT,
                    action = rejectAction(isSelected = true),
                    form = listOf(rejectButtonField()),
                    idempotencyKey = UUID.randomUUID(),
                    trigger = TriggerHandle(raw = "trigger-standup"),
                    message = MessageHandle(raw = "1700000000.000300"),
                    routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.STANDUP_PROMPT
                }

                then("a skip is recorded for the clicking member, folding the DM prompt that carried the button") {
                    intents.filterIsInstance<CommandIntent.RecordStandupSkip>().single() shouldBe
                        CommandIntent.RecordStandupSkip(
                            sessionUid = sessionUid,
                            userId = payload.actor.id,
                            confirmation =
                                StandupAnswerConfirmation.UpdateNotice(
                                    ref =
                                        MessageRef(
                                            conversation = ConversationTarget(id = payload.channelId),
                                            messageId = "1700000000.000300",
                                        ),
                                ),
                        )
                }

                then("the answer modal is not opened") {
                    intents.filterIsInstance<OutboundMessage.OpenModal>().shouldBeEmpty()
                    intents.size shouldBe 1
                }
            }
        }

        given("StandupFillContext receives a Skip button click with malformed routing extras") {
            val intentQueue = createIntentQueue()
            val context =
                StandupFillContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_PROMPT,
                    action = rejectAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    routingExtras = listOf(UUID.randomUUID().toString()),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("Slack still receives a success response and nothing is recorded") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }

        given("StandupFillContext receives malformed routing extras") {
            val intentQueue = createIntentQueue()
            val context =
                StandupFillContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.STANDUP_PROMPT,
                    action = approveAction(isSelected = true),
                    form = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                    routingExtras = listOf("not-a-uuid"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("Slack still receives a success response and no intent is emitted") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
