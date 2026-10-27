package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.approveAction
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.datePickerField
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.MeetingFormInput
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.entity.slash.RequestMeetingContextResult
import dev.notypie.domain.command.inbound.InboundField
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.plainTextField
import dev.notypie.domain.command.timePickerField
import dev.notypie.domain.command.toggleField
import dev.notypie.domain.command.unselectedTimePickerField
import dev.notypie.domain.command.usersField
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MeetingFormSubmissionTest :
    BehaviorSpec(body = {
        val commandBasicInfo = createCommandBasicInfo()
        val meetingDate = LocalDate.now().plusDays(1)
        val startTime = LocalTime.of(10, 0)
        val others = listOf("U_ALICE", "U_BOB", "U_CAROL")

        fun dateField(date: LocalDate = meetingDate) =
            datePickerField(date = date, format = MeetingFormInput.DATE_PATTERN)

        fun timeField(time: LocalTime) = timePickerField(time = time, format = MeetingFormInput.SIMPLE_TIME_PATTERN)

        fun submit(fields: List<InboundField>, intents: IntentQueue) =
            RequestMeetingContext(
                commandBasicInfo = commandBasicInfo,
                subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                intents = intents,
            ).handleInteraction(
                interaction =
                    createInboundInteraction(
                        idempotencyKey = commandBasicInfo.idempotencyKey,
                        detailType = CommandDetailType.MEETING_CREATE_REQUEST,
                        action = approveAction(isSelected = true),
                        form = fields,
                    ),
            )

        fun errorMarkdown(intents: IntentQueue): String =
            intents
                .snapshot()
                .single()
                .shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                .content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown

        given("a meeting form with the notice checkbox selected") {
            `when`("three participants are chosen") {
                val intents = createIntentQueue()
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                toggleField(isSelected = true),
                                usersField(userIds = others),
                                dateField(),
                                timeField(time = startTime),
                                unselectedTimePickerField(),
                            ),
                        intents = intents,
                    )

                then("one MEETING_APPROVAL_REQUEST approval is queued per participant") {
                    res.ok shouldBe true
                    val approvals = intents.snapshot().filterIsInstance<OutboundMessage.Approval>()
                    approvals.size shouldBe others.size
                    approvals.map { it.recipient }.toSet() shouldBe others.map { UserRef(id = it) }.toSet()
                    approvals.forEach { approval ->
                        approval.approval.commandDetailType shouldBe CommandDetailType.MEETING_APPROVAL_REQUEST
                        approval.approval.subTitle shouldBe VALID_TEST_TITLE
                        approval.approval.reason shouldBe VALID_TEST_REASON
                    }
                }
            }

            `when`("the checkbox is not selected") {
                val intents = createIntentQueue()
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                toggleField(isSelected = false),
                                usersField(userIds = others),
                                dateField(),
                                timeField(time = startTime),
                                unselectedTimePickerField(),
                            ),
                        intents = intents,
                    )

                then("no approval is queued") {
                    res.ok shouldBe true
                    intents.snapshot().filterIsInstance<OutboundMessage.Approval>().shouldBeEmpty()
                }
            }
        }

        given("the built meeting") {
            `when`("a second time field is selected") {
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                usersField(userIds = others),
                                dateField(),
                                timeField(time = startTime),
                                timeField(time = LocalTime.of(11, 30)),
                            ),
                        intents = createIntentQueue(),
                    )

                then("endAt is the second time on the chosen date") {
                    val meeting = res.shouldBeInstanceOf<RequestMeetingContextResult>().meeting
                    meeting.startAt shouldBe LocalDateTime.of(meetingDate, startTime)
                    meeting.endAt shouldBe LocalDateTime.of(meetingDate, LocalTime.of(11, 30))
                }
            }

            `when`("the form has no second time field") {
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                usersField(userIds = others),
                                dateField(),
                                timeField(time = startTime),
                            ),
                        intents = createIntentQueue(),
                    )

                then("endAt is startAt plus one hour") {
                    val meeting = res.shouldBeInstanceOf<RequestMeetingContextResult>().meeting
                    meeting.endAt shouldBe LocalDateTime.of(meetingDate, startTime).plusHours(1)
                }
            }

            `when`("title and reason are blank") {
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = ""),
                                plainTextField(text = " "),
                                usersField(userIds = others),
                                dateField(),
                                timeField(time = startTime),
                            ),
                        intents = createIntentQueue(),
                    )

                then("the defaults are used") {
                    val meeting = res.shouldBeInstanceOf<RequestMeetingContextResult>().meeting
                    meeting.title shouldBe MeetingFormInput.DEFAULT_MEETING_TITLE
                    meeting.reason shouldBe MeetingFormInput.DEFAULT_MEETING_REASON
                }
            }

            `when`("the host is among the chosen users") {
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                usersField(userIds = listOf(TEST_USER_ID) + others),
                                dateField(),
                                timeField(time = startTime),
                            ),
                        intents = createIntentQueue(),
                    )

                then("the host is not counted as a participant") {
                    val meeting = res.shouldBeInstanceOf<RequestMeetingContextResult>().meeting
                    meeting.memberIdSnapshot() shouldBe others.toSet()
                    meeting.host.userId shouldBe TEST_USER_ID
                }
            }
        }

        given("an invalid meeting form") {
            `when`("the start is in the past") {
                val intents = createIntentQueue()
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                usersField(userIds = others),
                                dateField(date = LocalDate.now().minusDays(1)),
                                timeField(time = startTime),
                            ),
                        intents = intents,
                    )

                then("it is rejected with the future-time hint") {
                    res.ok shouldBe false
                    errorMarkdown(intents = intents) shouldBe
                        "Make sure to choose a time in the *future* rather than now."
                }
            }

            `when`("the host is the only chosen user") {
                val intents = createIntentQueue()
                val res =
                    submit(
                        fields =
                            listOf(
                                plainTextField(text = VALID_TEST_TITLE),
                                plainTextField(text = VALID_TEST_REASON),
                                usersField(userIds = listOf(TEST_USER_ID)),
                                dateField(),
                                timeField(time = startTime),
                            ),
                        intents = intents,
                    )

                then("it is rejected as having no participants") {
                    res.ok shouldBe false
                    errorMarkdown(intents = intents) shouldBe "Select participants"
                }
            }
        }
    })
