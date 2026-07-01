package dev.notypie.domain.command.context

import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inboundField
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class AddParticipantSubmissionContextTest :
    BehaviorSpec({
        given("AddParticipantSubmissionContext receives a valid view_submission with selected users") {
            val meetingUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                AddParticipantSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.USERS,
                                rawValue = "U_A,U_B",
                                isSelected = true,
                                key = AddParticipantSubmissionContext.USERS_BLOCK_ID,
                            ),
                        ),
                    idempotencyKey = meetingUid,
                    routingExtras = listOf("U_HOST"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)
                val intents = intentQueue.drainSnapshot()

                then("the interaction succeeds") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.ADD_PARTICIPANT_SUBMIT
                }

                then("AddParticipant carries the meeting uid, requester, and the selected user ids") {
                    val add = intents.filterIsInstance<CommandIntent.AddParticipant>().single()
                    add.meetingUid shouldBe meetingUid
                    add.requesterId shouldBe "U_HOST"
                    add.participantUserIds shouldContainExactly listOf("U_A", "U_B")
                }
            }
        }

        given("AddParticipantSubmissionContext receives a submission with no users selected") {
            val meetingUid = UUID.randomUUID()
            val intentQueue = createIntentQueue()
            val context =
                AddParticipantSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
                    action = approveAction(isSelected = true),
                    form =
                        listOf(
                            inboundField(
                                kind = InboundFieldKind.USERS,
                                rawValue = "",
                                isSelected = true,
                                key = AddParticipantSubmissionContext.USERS_BLOCK_ID,
                            ),
                        ),
                    idempotencyKey = meetingUid,
                    routingExtras = listOf("U_HOST"),
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interaction = payload)

                then("no intents are emitted and Slack still gets success") {
                    result.ok shouldBe true
                    intentQueue.drainSnapshot().shouldBeEmpty()
                }
            }
        }

        given("AddParticipantSubmissionContext receives malformed meeting metadata") {
            val intentQueue = createIntentQueue()
            val context =
                AddParticipantSubmissionContext(
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )
            val payload =
                createInboundInteraction(
                    detailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
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
