package dev.notypie.repository.outbox

import dev.notypie.domain.command.createApprovalContents
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.domain.standup.createStandupAnswerDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

class OutboundMessageCodecTest :
    StringSpec({
        val basicInfo = createCommandBasicInfo()
        val target = ConversationTarget(id = basicInfo.channel)

        fun roundTrip(message: OutboundMessage): OutboundEnvelope =
            OutboundMessageCodec.decode(
                json =
                    OutboundMessageCodec.encode(
                        envelope = OutboundEnvelope(message = message, basicInfo = basicInfo),
                    ),
            )

        fun assertRoundTrips(message: OutboundMessage) {
            roundTrip(message = message) shouldBe OutboundEnvelope(message = message, basicInfo = basicInfo)
        }

        "ChannelMessage with Text round-trips, with and without detailType" {
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content = MessageContent.Text(headline = "Daily agenda", markdown = "agenda body"),
                        detailType = CommandDetailType.DAILY_AGENDA,
                    ),
            )
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content = MessageContent.Text(headline = null, markdown = "plain"),
                    ),
            )
        }

        "ChannelMessage with ErrorNotice round-trips" {
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content =
                            MessageContent.ErrorNotice(
                                className = "IllegalStateException",
                                message = "boom",
                                details = "stack details",
                            ),
                    ),
            )
        }

        "ChannelMessage with Schedule round-trips field-wise" {
            // TimeScheduleInfo.timeFormatter has no equals(), so whole-envelope equality cannot hold;
            // the mix-in drops it and the Kotlin default reconstructs it, compared field by field here.
            val original =
                TimeScheduleInfo(
                    scheduleName = "Team sync",
                    startTime = LocalDateTime.of(2026, 7, 10, 15, 0),
                    endTime = LocalDateTime.of(2026, 7, 10, 16, 0),
                )
            val decoded =
                roundTrip(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content = MessageContent.Schedule(headline = "Schedule", info = original),
                        ),
                ).message as OutboundMessage.ChannelMessage
            val info = (decoded.content as MessageContent.Schedule).info
            info.scheduleName shouldBe original.scheduleName
            info.startTime shouldBe original.startTime
            info.endTime shouldBe original.endTime
            info.timeZone shouldBe original.timeZone
            info.toString() shouldBe original.toString()
        }

        "ChannelMessage with Form round-trips including String select values" {
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content =
                            MessageContent.Form(
                                headline = "Approval Requests",
                                fields =
                                    listOf(
                                        SelectionContents(
                                            title = "Request type",
                                            explanation = "Pick one",
                                            placeholderText = "Select...",
                                            contents =
                                                listOf(
                                                    SelectBoxDetails(
                                                        name = "Pull Requests",
                                                        value = "GIT_PULL_REQUEST",
                                                    ),
                                                    SelectBoxDetails(
                                                        name = "Logs",
                                                        isMarkDown = true,
                                                        value = "GET_LOGS",
                                                    ),
                                                ),
                                        ),
                                    ),
                                reason = TextInputContents(title = "Reason", placeholderText = "Why?"),
                                approval = createApprovalContents(),
                            ),
                    ),
            )
        }

        "ChannelMessage with MeetingRequest round-trips" {
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content = MessageContent.MeetingRequest(approval = createApprovalContents()),
                    ),
            )
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content = MessageContent.MeetingRequest(approval = null),
                    ),
            )
        }

        "ChannelMessage with StandupSummary round-trips ZoneId and Instant fields" {
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content =
                            MessageContent.StandupSummary(
                                routineName = "Daily Standup",
                                sessionDate = LocalDate.of(2026, 5, 1),
                                members = listOf(createRoutineMemberDto()),
                                answers = listOf(createStandupAnswerDto()),
                                questions = listOf("What did you do yesterday?"),
                            ),
                    ),
            )
        }

        "Ephemeral with Text round-trips with recipient and detailType" {
            assertRoundTrips(
                message =
                    OutboundMessage.Ephemeral(
                        target = target,
                        recipient = UserRef(id = "U_RECIPIENT"),
                        content = MessageContent.Text(headline = null, markdown = "canceled"),
                        detailType = CommandDetailType.CANCEL_MEETING,
                    ),
            )
            assertRoundTrips(
                message =
                    OutboundMessage.Ephemeral(
                        target = target,
                        content = MessageContent.Text(headline = null, markdown = "for the publisher"),
                    ),
            )
        }

        "Ephemeral with MeetingList round-trips MeetingDto graphs" {
            assertRoundTrips(
                message =
                    OutboundMessage.Ephemeral(
                        target = target,
                        content =
                            MessageContent.MeetingList(
                                meetings = listOf(createMeetingDto(), createMeetingDto(title = "Second")),
                                currentUserId = "U_VIEWER",
                            ),
                    ),
            )
        }

        "Approval round-trips ApprovalContents and routing extras" {
            assertRoundTrips(
                message =
                    OutboundMessage.Approval(
                        target = target,
                        recipient = UserRef(id = "U_MEMBER"),
                        approval = createApprovalContents(commandDetailType = CommandDetailType.STANDUP_PROMPT),
                        routingExtras = listOf("session-uid", "routine-uid"),
                    ),
            )
        }

        "Notice round-trips mention refs" {
            assertRoundTrips(
                message =
                    OutboundMessage.Notice(
                        target = target,
                        mentions = listOf(UserRef(id = "U_A"), UserRef(id = "U_B")),
                        message = "please check",
                    ),
            )
        }

        "UpdateMessage round-trips MessageRef and detailType" {
            assertRoundTrips(
                message =
                    OutboundMessage.UpdateMessage(
                        ref =
                            MessageRef(
                                conversation = ConversationTarget(id = "D_NOTICE"),
                                messageId = "1700000000.000700",
                            ),
                        content = MessageContent.Text(headline = null, markdown = "updated"),
                        detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    ),
            )
        }

        "ReplaceMessage round-trips the reply handle" {
            assertRoundTrips(
                message =
                    OutboundMessage.ReplaceMessage(
                        handle = ResponseReplaceHandle(raw = "https://hooks.example.com/actions/123"),
                        content = MessageContent.Text(headline = null, markdown = "replaced"),
                    ),
            )
        }

        "OpenModal is not outbox-bound and fails fast on round-trip" {
            val modal =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-123"),
                    form = ModalForm.StandupSetup(creatorId = "U_C", commandChannel = ConversationTarget(id = "C_CMD")),
                )
            shouldThrow<OutboundMessageCodecException> { roundTrip(message = modal) }
        }

        "decode fails fast on an unknown message subtype" {
            shouldThrow<OutboundMessageCodecException> {
                OutboundMessageCodec.decode(
                    json = """{"message":{"@type":"NoSuchType","x":1},"basicInfo":{}}""",
                )
            }
        }

        "decode fails fast on malformed json" {
            shouldThrow<OutboundMessageCodecException> { OutboundMessageCodec.decode(json = "{ not json") }
        }
    })
