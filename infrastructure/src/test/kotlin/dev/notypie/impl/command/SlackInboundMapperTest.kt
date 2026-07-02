package dev.notypie.impl.command

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.InboundActionRole
import dev.notypie.domain.command.inbound.InboundFieldKeys
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.States
import dev.notypie.impl.command.slack.createInteractionPayloadInput
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class SlackInboundMapperTest :
    BehaviorSpec({

        given("a synthesized view_submission payload (APPLY_BUTTON currentAction, no message_ts)") {
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("mapped to the neutral inbound model") {
                val inbound = payload.toInbound()

                then("the action becomes an APPROVE role that is selected") {
                    inbound.action.role shouldBe InboundActionRole.APPROVE
                    inbound.action.isSelected shouldBe true
                }

                then("the message handle is null because there is no container message_ts") {
                    inbound.message.shouldBeNull()
                }
            }
        }

        given("a block_actions payload carrying a container message_ts") {
            val base =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
                    currentAction = States(type = ActionElementTypes.REJECT_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                )
            val payload = base.copy(container = base.container.copy(messageTs = "1700000000.000050"))

            `when`("mapped to the neutral inbound model") {
                val inbound = payload.toInbound()

                then("the message handle carries the message_ts") {
                    inbound.message?.raw shouldBe "1700000000.000050"
                }

                then("the REJECT action maps to the REJECT role") {
                    inbound.action.role shouldBe InboundActionRole.REJECT
                }

                then("a block_actions interaction carries no typed submission") {
                    inbound.submission.shouldBeNull()
                }
            }
        }

        given("a standup-answer view_submission with unordered per-question fields") {
            val sessionUid = UUID.randomUUID()
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "  Work on #13  ",
                                blockId = "${InboundFieldKeys.STANDUP_ANSWER_QUESTION_PREFIX}1",
                            ),
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "Finished #12",
                                blockId = "${InboundFieldKeys.STANDUP_ANSWER_QUESTION_PREFIX}0",
                            ),
                        ),
                    idempotencyKey = sessionUid,
                ).copy(routingExtras = listOf("U_STANDUP", "D_NOTICE", "1700000000.000300"))

            `when`("mapped to the neutral inbound model") {
                val submission = payload.toInbound().submission

                then("it builds a StandupAnswer with routing plus trimmed, index-sorted answers") {
                    val standup = submission.shouldBeInstanceOf<InboundSubmission.StandupAnswer>()
                    standup.sessionUidRaw shouldBe sessionUid.toString()
                    standup.userId shouldBe "U_STANDUP"
                    standup.noticeChannel shouldBe "D_NOTICE"
                    standup.noticeMessageTs shouldBe "1700000000.000300"
                    standup.answers shouldContainExactly listOf("Finished #12", "Work on #13")
                }
            }
        }

        given("States covering every element type") {
            fun field(type: ActionElementTypes) =
                States(type = type, isSelected = true, selectedValue = "v", blockId = "b").toInboundField()

            then("each Slack element maps to its neutral field kind, unknowns collapse to UNKNOWN") {
                field(type = ActionElementTypes.PLAIN_TEXT_INPUT).kind shouldBe InboundFieldKind.TEXT
                field(type = ActionElementTypes.STATIC_SELECT).kind shouldBe InboundFieldKind.CHOICE
                field(type = ActionElementTypes.RADIO_BUTTONS).kind shouldBe InboundFieldKind.CHOICE
                field(type = ActionElementTypes.MULTI_STATIC_SELECT).kind shouldBe InboundFieldKind.MULTI_CHOICE
                field(type = ActionElementTypes.MULTI_USERS_SELECT).kind shouldBe InboundFieldKind.USERS
                field(type = ActionElementTypes.CONVERSATIONS_SELECT).kind shouldBe InboundFieldKind.CONVERSATION
                field(type = ActionElementTypes.DATE_PICKER).kind shouldBe InboundFieldKind.DATE
                field(type = ActionElementTypes.TIME_PICKER).kind shouldBe InboundFieldKind.TIME
                field(type = ActionElementTypes.CHECKBOX).kind shouldBe InboundFieldKind.TOGGLE
                field(type = ActionElementTypes.BUTTON).kind shouldBe InboundFieldKind.UNKNOWN
                field(type = ActionElementTypes.UNKNOWN).kind shouldBe InboundFieldKind.UNKNOWN
            }

            then("blockId, isSelected and selectedValue carry over") {
                val mapped = field(type = ActionElementTypes.PLAIN_TEXT_INPUT)
                mapped.key shouldBe "b"
                mapped.isSelected shouldBe true
                mapped.rawValue shouldBe "v"
            }
        }

        given("currentAction covering each role") {
            then("APPLY/REJECT/BUTTON/other map to APPROVE/REJECT/ACTIVATE/PASSIVE") {
                States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true).toInboundAction().role shouldBe
                    InboundActionRole.APPROVE
                States(type = ActionElementTypes.REJECT_BUTTON, isSelected = true).toInboundAction().role shouldBe
                    InboundActionRole.REJECT
                States(type = ActionElementTypes.BUTTON, isSelected = true).toInboundAction().role shouldBe
                    InboundActionRole.ACTIVATE
                States(type = ActionElementTypes.STATIC_SELECT, isSelected = true).toInboundAction().role shouldBe
                    InboundActionRole.PASSIVE
            }
        }

        given("toInboundCommand") {
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.APPROVAL_REQUEST,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("converting to InboundCommand") {
                val commandData = payload.toInboundCommand()

                then("identity fields map from the Slack payload") {
                    commandData.appId shouldBe payload.apiAppId
                    commandData.appToken shouldBe payload.token
                    commandData.actorId shouldBe payload.user.id
                    commandData.actorName shouldBe payload.user.name
                    commandData.channel shouldBe payload.channel.id
                    commandData.channelName shouldBe payload.channel.name
                    commandData.kind shouldBe InboundKind.INTERACTION
                }

                then("the payload is the neutral inbound interaction") {
                    val interaction = commandData.payload.shouldBeInstanceOf<InboundInteraction>()
                    interaction.detailType shouldBe payload.type
                    interaction.actor.id shouldBe payload.user.id
                }
            }
        }
    })
