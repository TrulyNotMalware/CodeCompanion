package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.StandupAnswerSubmissionContext
import dev.notypie.domain.command.inbound.InboundSubmission
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
                    // The mapper has already trimmed and ordered the answers by question index, so
                    // the context consumes them as-is.
                    submission =
                        InboundSubmission.StandupAnswer(
                            sessionUidRaw = sessionUid.toString(),
                            userId = "U_STANDUP",
                            noticeChannel = "D_NOTICE",
                            noticeMessageTs = "1700000000.000300",
                            answers = listOf("Finished #12", "Work on #13"),
                        ),
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
                    submission =
                        InboundSubmission.StandupAnswer(
                            sessionUidRaw = "not-a-uuid",
                            userId = "U_STANDUP",
                            noticeChannel = "D_NOTICE",
                            noticeMessageTs = "1700000000.000300",
                            answers = emptyList(),
                        ),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }
    })
