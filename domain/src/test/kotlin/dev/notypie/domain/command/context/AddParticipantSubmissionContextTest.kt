package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.MULTI_USERS_SELECT,
                                isSelected = true,
                                selectedValue = "U_A,U_B",
                                blockId = AddParticipantSubmissionContext.USERS_BLOCK_ID,
                            ),
                        ),
                    idempotencyKey = meetingUid,
                ).copy(
                    routingExtras = listOf("U_HOST"),
                    privateMetadata = "$meetingUid,${CommandDetailType.ADD_PARTICIPANT_SUBMIT.name},U_HOST",
                )

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)
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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.MULTI_USERS_SELECT,
                                isSelected = true,
                                selectedValue = "",
                                blockId = AddParticipantSubmissionContext.USERS_BLOCK_ID,
                            ),
                        ),
                    idempotencyKey = meetingUid,
                ).copy(routingExtras = listOf("U_HOST"))

            `when`("handleInteraction is invoked") {
                val result = context.handleInteraction(interactionPayload = payload)

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
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
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
