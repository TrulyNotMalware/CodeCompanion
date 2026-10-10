package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_LIST_HANDLE
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.CancelMeetingContext
import dev.notypie.domain.command.inbound.ReplyHandle
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID

class CancelMeetingContextTest :
    BehaviorSpec({
        val meetingUid = UUID.randomUUID()

        fun cancelIntents(routingExtras: List<String>, reply: ReplyHandle): List<CommandIntent.CancelMeeting> {
            val intentQueue = createIntentQueue()
            val result =
                CancelMeetingContext(commandBasicInfo = createCommandBasicInfo(), intents = intentQueue)
                    .handleInteraction(
                        interaction =
                            createInboundInteraction(
                                detailType = CommandDetailType.CANCEL_MEETING,
                                action = approveAction(isSelected = true),
                                routingExtras = routingExtras,
                                reply = reply,
                            ),
                    )
            result.ok shouldBe true
            return intentQueue.drainSnapshot().filterIsInstance<CommandIntent.CancelMeeting>()
        }

        given("a Cancel click on a /meetup list row") {
            `when`("the click carries the list message's reply handle") {
                val reply = ReplyHandle(raw = TEST_LIST_HANDLE)
                val intents = cancelIntents(routingExtras = listOf(meetingUid.toString()), reply = reply)

                then("the cancel intent names the meeting, the actor and that handle, so the list can be closed") {
                    intents shouldBe
                        listOf(
                            CommandIntent.CancelMeeting(
                                meetingUid = meetingUid,
                                requesterId = createInboundInteraction().actor.id,
                                listHandle = ResponseReplaceHandle(raw = reply.raw),
                            ),
                        )
                }
            }

            `when`("the click carries no reply handle") {
                val intents =
                    cancelIntents(routingExtras = listOf(meetingUid.toString()), reply = ReplyHandle(raw = ""))

                then("the cancel intent has no list handle, so the host gets the ephemeral reply") {
                    intents.single().listHandle shouldBe null
                }
            }

            `when`("the meeting uid is malformed") {
                val intents = cancelIntents(routingExtras = listOf("not-a-uuid"), reply = ReplyHandle(raw = "x"))

                then("no intent is emitted and Slack still gets success") {
                    intents.shouldBeEmpty()
                }
            }
        }
    })
