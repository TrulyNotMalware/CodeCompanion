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

        given("reschedule and add-participant submissions whose modal metadata carries a /meetup list handle") {
            val submitTypes =
                listOf(CommandDetailType.MEETING_RESCHEDULE_SUBMIT, CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT)

            fun submitOf(type: CommandDetailType, routingExtras: List<String>): InboundInteraction =
                createInteractionPayloadInput(
                    commandDetailType = type,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states = emptyList(),
                    idempotencyKey = UUID.randomUUID(),
                ).copy(routingExtras = routingExtras).toInbound()

            fun InboundSubmission?.handleUrl(): String? =
                when (this) {
                    is InboundSubmission.RescheduleMeeting -> listHandle?.raw
                    is InboundSubmission.AddParticipant -> listHandle?.raw
                    else -> error("not a list-handle submission: $this")
                }

            `when`("the handle slot is present but blank") {
                val inbound =
                    submitTypes.map { type -> submitOf(type = type, routingExtras = listOf("U_HOST", "C1", "")) }

                then("neither submission carries a handle") {
                    inbound.map { it.submission.handleUrl() } shouldBe listOf(null, null)
                }
            }

            `when`("the handle slot holds a response URL followed by another extra") {
                val inbound =
                    submitTypes.map { type ->
                        submitOf(
                            type = type,
                            routingExtras = listOf("U_HOST", "C1", "https://hooks.slack.test/list", "EXTRA"),
                        )
                    }

                then("both submissions carry it and only the handle slot leaves the routing extras") {
                    inbound.map { it.submission.handleUrl() } shouldBe
                        listOf("https://hooks.slack.test/list", "https://hooks.slack.test/list")
                    inbound.map { it.routingExtras } shouldBe
                        listOf(listOf("U_HOST", "C1", "EXTRA"), listOf("U_HOST", "C1", "EXTRA"))
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

        given("a cve subscribe view_submission with a comma-joined multi-select") {
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.MULTI_STATIC_SELECT,
                                isSelected = true,
                                selectedValue = "kotlin, spring",
                                blockId = InboundFieldKeys.CVE_SUBSCRIBE_TOPICS,
                            ),
                        ),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("mapped to the neutral inbound model") {
                val submission = payload.toInbound().submission

                then("it builds a CveSubscribe with trimmed topic keys") {
                    val subscribe = submission.shouldBeInstanceOf<InboundSubmission.CveSubscribe>()
                    subscribe.topicKeys shouldContainExactly listOf("kotlin", "spring")
                }
            }
        }

        given("a cve unsubscribe view_submission") {
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.MULTI_STATIC_SELECT,
                                isSelected = true,
                                selectedValue = "cve-java",
                                blockId = InboundFieldKeys.CVE_UNSUBSCRIBE_TOPICS,
                            ),
                        ),
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("mapped to the neutral inbound model") {
                val submission = payload.toInbound().submission

                then("it builds a CveUnsubscribe with the selected key") {
                    val unsubscribe = submission.shouldBeInstanceOf<InboundSubmission.CveUnsubscribe>()
                    unsubscribe.topicKeys shouldContainExactly listOf("cve-java")
                }
            }
        }

        given("a standup-setup view_submission carrying all eight form fields") {
            val setupKey = UUID.randomUUID()

            fun stateOf(type: ActionElementTypes, blockId: String, value: String) =
                States(type = type, isSelected = value.isNotBlank(), selectedValue = value, blockId = blockId)

            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            stateOf(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_NAME,
                                value = "daily",
                            ),
                            stateOf(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_QUESTIONS,
                                value = "Done?\nBlocked?",
                            ),
                            stateOf(
                                type = ActionElementTypes.MULTI_USERS_SELECT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_MEMBERS,
                                value = "U_A,U_B",
                            ),
                            stateOf(
                                type = ActionElementTypes.CONVERSATIONS_SELECT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_SUMMARY_CHANNEL,
                                value = "C_SUMMARY",
                            ),
                            stateOf(
                                type = ActionElementTypes.MULTI_STATIC_SELECT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_WEEKDAYS,
                                value = "MONDAY, FRIDAY",
                            ),
                            stateOf(
                                type = ActionElementTypes.TIME_PICKER,
                                blockId = InboundFieldKeys.STANDUP_SETUP_TIME,
                                value = "09:15",
                            ),
                            stateOf(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_CUTOFF,
                                value = "90",
                            ),
                            stateOf(
                                type = ActionElementTypes.STATIC_SELECT,
                                blockId = InboundFieldKeys.STANDUP_SETUP_TIMEZONE,
                                value = "Asia/Seoul",
                            ),
                        ),
                    idempotencyKey = setupKey,
                ).copy(routingExtras = listOf("U_CREATOR", "C_COMMAND"))

            `when`("mapped to the neutral inbound model") {
                val submission = payload.toInbound().submission

                then("every field lands in its own StandupSetup slot, with routing from the extras") {
                    submission shouldBe
                        InboundSubmission.StandupSetup(
                            idempotencyKeyRaw = setupKey.toString(),
                            creatorId = "U_CREATOR",
                            commandChannel = "C_COMMAND",
                            name = "daily",
                            questionsRaw = "Done?\nBlocked?",
                            membersRaw = "U_A,U_B",
                            summaryChannel = "C_SUMMARY",
                            weekdaysRaw = "MONDAY, FRIDAY",
                            timeRaw = "09:15",
                            cutoffRaw = "90",
                            timezoneRaw = "Asia/Seoul",
                        )
                }
            }

            `when`("the form carries no fields") {
                val submission = payload.copy(states = emptyList()).toInbound().submission

                then("every form slot is blank while the routing slots stay filled") {
                    val setup = submission.shouldBeInstanceOf<InboundSubmission.StandupSetup>()
                    setup.creatorId shouldBe "U_CREATOR"
                    setup.commandChannel shouldBe "C_COMMAND"
                    listOf(
                        setup.name,
                        setup.questionsRaw,
                        setup.membersRaw,
                        setup.summaryChannel,
                        setup.weekdaysRaw,
                        setup.timeRaw,
                        setup.cutoffRaw,
                        setup.timezoneRaw,
                    ).forEach { it shouldBe "" }
                }
            }
        }

        given("an add-participant view_submission with a multi-user select") {
            val meetingUid = UUID.randomUUID()
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.MULTI_USERS_SELECT,
                                isSelected = true,
                                selectedValue = "U_NEW1,U_NEW2",
                                blockId = InboundFieldKeys.ADD_PARTICIPANT_USERS,
                            ),
                        ),
                    idempotencyKey = meetingUid,
                ).copy(routingExtras = listOf("U_HOST", "C_ORIGIN", "https://hooks.slack.test/list"))

            `when`("mapped to the neutral inbound model") {
                val inbound = payload.toInbound()

                then("the selected users, meeting and requester land in AddParticipant") {
                    val add = inbound.submission.shouldBeInstanceOf<InboundSubmission.AddParticipant>()
                    add.meetingUidRaw shouldBe meetingUid.toString()
                    add.requesterId shouldBe "U_HOST"
                    add.participantUserIdsRaw shouldBe "U_NEW1,U_NEW2"
                    add.listHandle?.raw shouldBe "https://hooks.slack.test/list"
                }
            }

            `when`("the select state is missing") {
                val add = payload.copy(states = emptyList()).toInbound().submission

                then("the raw users are blank") {
                    add.shouldBeInstanceOf<InboundSubmission.AddParticipant>().participantUserIdsRaw shouldBe ""
                }
            }
        }

        given("a decline-reason view_submission with a reason select and a detail input") {
            val meetingKey = UUID.randomUUID()
            val payload =
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
                    currentAction = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
                    states =
                        listOf(
                            States(
                                type = ActionElementTypes.STATIC_SELECT,
                                isSelected = true,
                                selectedValue = "SCHEDULE_CONFLICT",
                                blockId = "reason_block",
                            ),
                            States(
                                type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                isSelected = true,
                                selectedValue = "double booked",
                                blockId = "detail_block",
                            ),
                        ),
                    idempotencyKey = meetingKey,
                ).copy(routingExtras = listOf("U_GUEST", "D_NOTICE", "1700000000.000900"))

            `when`("mapped to the neutral inbound model") {
                val submission = payload.toInbound().submission

                then("the choice becomes the reason and the text becomes the detail") {
                    submission shouldBe
                        InboundSubmission.DeclineReason(
                            meetingIdempotencyKeyRaw = meetingKey.toString(),
                            participantUserId = "U_GUEST",
                            noticeChannel = "D_NOTICE",
                            noticeMessageTs = "1700000000.000900",
                            reasonRaw = "SCHEDULE_CONFLICT",
                            detailRaw = "double booked",
                        )
                }
            }

            `when`("the optional detail input is absent") {
                val submission = payload.copy(states = payload.states.take(n = 1)).toInbound().submission

                then("the reason is kept and the detail is blank") {
                    val decline = submission.shouldBeInstanceOf<InboundSubmission.DeclineReason>()
                    decline.reasonRaw shouldBe "SCHEDULE_CONFLICT"
                    decline.detailRaw shouldBe ""
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
