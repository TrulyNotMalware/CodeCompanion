package dev.notypie.repository.outbox

import dev.notypie.domain.command.createApprovalContents
import dev.notypie.domain.command.createCommandBasicInfo
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
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

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

        "ChannelMessage with Text round-trips, with and without detailType and threadId" {
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
            assertRoundTrips(
                message =
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content = MessageContent.Text(headline = "AI assistant", markdown = "threaded reply"),
                        detailType = CommandDetailType.AGENT_CONVERSE,
                        threadId = "1700000000.000100",
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

        "a ReplaceMessage's fallback ephemeral round-trips with its subtype, and an absent one adds no key" {
            val fallback =
                OutboundMessage.Ephemeral(
                    target = target,
                    recipient = UserRef(id = "U_HOST"),
                    content = MessageContent.Text(headline = null, markdown = "Meeting canceled."),
                    detailType = CommandDetailType.CANCEL_MEETING,
                )
            val withFallback =
                OutboundMessage.ReplaceMessage(
                    handle = ResponseReplaceHandle(raw = "https://hooks.example.com/actions/123"),
                    content = MessageContent.Text(headline = null, markdown = "*Sync* canceled."),
                    fallback = fallback,
                )
            assertRoundTrips(message = withFallback)
            OutboundMessageCodec.encode(
                envelope = OutboundEnvelope(message = withFallback.copy(fallback = null), basicInfo = basicInfo),
            ) shouldNotContain "fallback"
        }

        "pre-narrowing update/replace rows decode, and the encoded wire shape is frozen" {
            val fixtureBasicInfo =
                createCommandBasicInfo(idempotencyKey = UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val basicInfoJson =
                "\"basicInfo\":{\"appId\":\"A12ABCDEFG\",\"appToken\":\"I_AM_TEST_TOKEN\"," +
                    "\"publisherId\":\"U012ABCDEFG\",\"channel\":\"C012ABCDEFG\"," +
                    "\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\"}"
            val updateFixture =
                "{\"message\":{\"@type\":\"UpdateMessage\",\"ref\":{\"conversation\":\"D_NOTICE\"," +
                    "\"messageId\":\"1700000000.000700\"},\"content\":{\"@type\":\"Text\"," +
                    "\"headline\":null,\"markdown\":\"updated\"},\"detailType\":\"STANDUP_ANSWER_SUBMIT\"},$basicInfoJson}"
            val replaceFixture =
                "{\"message\":{\"@type\":\"ReplaceMessage\",\"handle\":\"https://hooks.example.com/actions/123\"," +
                    "\"content\":{\"@type\":\"Text\",\"headline\":null,\"markdown\":\"replaced\"}},$basicInfoJson}"
            val updateEnvelope =
                OutboundEnvelope(
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
                    basicInfo = fixtureBasicInfo,
                )
            val replaceEnvelope =
                OutboundEnvelope(
                    message =
                        OutboundMessage.ReplaceMessage(
                            handle = ResponseReplaceHandle(raw = "https://hooks.example.com/actions/123"),
                            content = MessageContent.Text(headline = null, markdown = "replaced"),
                        ),
                    basicInfo = fixtureBasicInfo,
                )

            OutboundMessageCodec.decode(json = updateFixture) shouldBe updateEnvelope
            OutboundMessageCodec.decode(json = replaceFixture) shouldBe replaceEnvelope
            OutboundMessageCodec.encode(envelope = updateEnvelope) shouldBe updateFixture
            OutboundMessageCodec.encode(envelope = replaceEnvelope) shouldBe replaceFixture
        }

        "an envelope carrying the rest of a chain round-trips every part with its subtype" {
            val envelope =
                OutboundEnvelope(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = target,
                            content = MessageContent.Text(headline = "Answer (1/3)", markdown = "first"),
                            threadId = "1700000000.000100",
                        ),
                    basicInfo = basicInfo,
                    continuation =
                        listOf(
                            OutboundMessage.ChannelMessage(
                                target = target,
                                content = MessageContent.Text(headline = "Answer (2/3)", markdown = "second"),
                                threadId = "1700000000.000100",
                            ),
                            OutboundMessage.Ephemeral(
                                target = target,
                                recipient = UserRef(id = "U_R"),
                                content = MessageContent.Text(headline = "Answer (3/3)", markdown = "third"),
                            ),
                        ),
                )

            OutboundMessageCodec.decode(json = OutboundMessageCodec.encode(envelope = envelope)) shouldBe envelope
        }

        "an envelope without a continuation writes no continuation field" {
            val json =
                OutboundMessageCodec.encode(
                    envelope =
                        OutboundEnvelope(
                            message =
                                OutboundMessage.ChannelMessage(
                                    target = target,
                                    content = MessageContent.Text(headline = null, markdown = "plain"),
                                ),
                            basicInfo = basicInfo,
                        ),
                )

            json shouldNotContain "continuation"
        }

        "next hands the first queued part the rest of the chain, and the last part has none" {
            val parts =
                (1..3).map { index ->
                    OutboundMessage.ChannelMessage(
                        target = target,
                        content = MessageContent.Text(headline = "($index/3)", markdown = "part $index"),
                    )
                }
            val head = OutboundEnvelope(message = parts[0], basicInfo = basicInfo, continuation = parts.drop(n = 1))

            head.next() shouldBe
                OutboundEnvelope(message = parts[1], basicInfo = basicInfo, continuation = listOf(parts[2]))
            head.next()?.next() shouldBe OutboundEnvelope(message = parts[2], basicInfo = basicInfo)
            head.next()?.next()?.next() shouldBe null
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
