package dev.notypie.impl.command

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.InboundActionRole
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.States
import dev.notypie.impl.command.slack.createInteractionPayloadInput
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class SlackInboundMapperTest :
    BehaviorSpec({

        given("a synthesized view_submission payload (APPLY_BUTTON currentAction, no message_ts)") {
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
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
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
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
                    commandDetailType = CommandDetailType.APPROVAL_FORM,
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
