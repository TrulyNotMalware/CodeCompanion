package dev.notypie.impl.command

import com.slack.api.model.block.ActionsBlock
import com.slack.api.model.block.InputBlock
import com.slack.api.model.block.element.BlockElement
import com.slack.api.model.block.element.ButtonElement
import com.slack.api.model.block.element.ConversationsSelectElement
import com.slack.api.model.block.element.DatePickerElement
import com.slack.api.model.block.element.MultiStaticSelectElement
import com.slack.api.model.block.element.MultiUsersSelectElement
import com.slack.api.model.block.element.PlainTextInputElement
import com.slack.api.model.block.element.StaticSelectElement
import com.slack.api.model.block.element.TimePickerElement
import com.slack.api.model.view.View
import com.slack.api.util.json.GsonFactory
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.StandupAnswerConfirmation
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.impl.command.event.OpenViewEvent
import dev.notypie.impl.command.event.createOpenViewEvent
import dev.notypie.templates.AddParticipantModalIds
import dev.notypie.templates.ButtonType
import dev.notypie.templates.DeclineReasonModalIds
import dev.notypie.templates.MeetingActionIds
import dev.notypie.templates.ModalBlockBuilder
import dev.notypie.templates.ModalTemplateBuilder
import dev.notypie.templates.RescheduleMeetingModalIds
import dev.notypie.templates.StandupModalIds
import dev.notypie.templates.StandupSetupModalIds
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class ViewSubmissionChannelRoutingRegressionTest :
    BehaviorSpec({
        val templateBuilder =
            ModalTemplateBuilder(
                modalBlockBuilder = ModalBlockBuilder(),
                restRequester = mockk(),
                slackApiToken = TEST_BOT_TOKEN,
            )
        val parser = SlackInteractionRequestParser()

        fun extractPrivateMetadata(modalViewJson: String): String =
            GsonFactory.createSnakeCase().fromJson(modalViewJson, View::class.java).privateMetadata

        fun runThroughDomain(viewSubmissionPayload: String): Pair<CommandOutput, List<CommandEffect>> {
            val interactionPayload = parser.parseStringPayload(payload = viewSubmissionPayload)
            val command =
                InteractionCommand(
                    appName = "routing-regression-test",
                    idempotencyKey = UUID.randomUUID(),
                    commandData = interactionPayload.toInboundCommand(),
                    actorRole = UserRole.USER,
                )
            val output = command.handleEvent()
            return output to command.drainIntents()
        }

        given("the reschedule-meeting modal's private_metadata feeds the view_submission reader") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST"
            val originChannel = "C_ORIGIN_RESCHEDULE"
            val modalJson =
                templateBuilder.rescheduleMeetingModalViewJson(
                    meetingUid = meetingUid,
                    currentStartAt = LocalDateTime.of(2026, 7, 1, 14, 30),
                    requesterId = requesterId,
                    channel = originChannel,
                    listResponseUrl = "",
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("type, idempotencyKey, and the origin channel are recovered from routingExtras[1]") {
                    interactionPayload.type shouldBe CommandDetailType.MEETING_RESCHEDULE_SUBMIT
                    interactionPayload.idempotencyKey shouldBe meetingUid.toString()
                    interactionPayload.channel.id shouldBe originChannel
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, _) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("CommandOutput threads the same origin channel end-to-end") {
                    output.ok shouldBe true
                    output.channel shouldBe originChannel
                }
            }
        }

        given("the add-participant modal's private_metadata feeds the view_submission reader") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_AP"
            val originChannel = "C_ORIGIN_ADD_PARTICIPANT"
            val modalJson =
                templateBuilder.addParticipantModalViewJson(
                    meetingUid = meetingUid,
                    requesterId = requesterId,
                    channel = originChannel,
                    listResponseUrl = "",
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = AddParticipantModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("type, idempotencyKey, and the origin channel are recovered from routingExtras[1]") {
                    interactionPayload.type shouldBe CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT
                    interactionPayload.idempotencyKey shouldBe meetingUid.toString()
                    interactionPayload.channel.id shouldBe originChannel
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, _) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("CommandOutput threads the same origin channel end-to-end") {
                    output.ok shouldBe true
                    output.channel shouldBe originChannel
                }
            }
        }

        given("the standup-setup modal's private_metadata feeds the view_submission reader") {
            val setupKey = UUID.randomUUID()
            val creatorId = "U_C"
            val commandChannel = "C_CMD"
            val modalJson =
                templateBuilder.standupSetupModalViewJson(
                    idempotencyKey = setupKey,
                    creatorId = creatorId,
                    commandChannel = commandChannel,
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = StandupSetupModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("routingExtras exposes creator + command channel; basicInfo.channel stays blank") {
                    interactionPayload.type shouldBe CommandDetailType.STANDUP_SETUP_SUBMIT
                    interactionPayload.idempotencyKey shouldBe setupKey.toString()
                    interactionPayload.routingExtras shouldBe listOf(creatorId, commandChannel)
                    interactionPayload.channel.id shouldBe ""
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("CreateStandupRoutine carries the creator and command channel recovered from routingExtras") {
                    output.ok shouldBe true
                    val create = effects.filterIsInstance<CommandIntent.CreateStandupRoutine>().single()
                    create.creatorId shouldBe creatorId
                    create.commandChannel shouldBe commandChannel
                }
            }
        }

        given("the standup-answer modal's private_metadata feeds the view_submission reader") {
            val sessionUid = UUID.randomUUID()
            val userId = "U_STANDUP"
            val noticeChannel = "D_NOTICE_ANSWER"
            val noticeMessageTs = "1700000000.000700"
            val modalJson =
                templateBuilder.standupModalViewJson(
                    routineName = "Daily Standup",
                    sessionDate = LocalDate.of(2026, 5, 4),
                    sessionUid = sessionUid,
                    userId = userId,
                    noticeChannel = noticeChannel,
                    noticeMessageTs = noticeMessageTs,
                    questions = listOf("What did you do yesterday?"),
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = StandupModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("routingExtras exposes user, notice channel, and message ts in writer order") {
                    interactionPayload.type shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                    interactionPayload.idempotencyKey shouldBe sessionUid.toString()
                    interactionPayload.routingExtras shouldBe listOf(userId, noticeChannel, noticeMessageTs)
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("UpdateMessage collapses the notice DM at the recovered channel and ts") {
                    output.ok shouldBe true
                    val update = effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe noticeChannel
                    update.ref.messageId shouldBe noticeMessageTs
                    update.detailType shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                }
            }
        }

        given("the standup-answer modal opened by /standup answer has a command channel but no message ts") {
            val sessionUid = UUID.randomUUID()
            val userId = "U_STANDUP_SLASH"
            val commandChannel = "C_CMD_ANSWER"
            val modalJson =
                templateBuilder.standupModalViewJson(
                    routineName = "Daily Standup",
                    sessionDate = LocalDate.of(2026, 5, 4),
                    sessionUid = sessionUid,
                    userId = userId,
                    noticeChannel = commandChannel,
                    noticeMessageTs = "",
                    questions = listOf("What did you do yesterday?"),
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = StandupModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                    stateValues =
                        stateValuesJson(
                            "${StandupModalIds.BLOCK_ID_PREFIX}0" to plainTextInputStateJson(value = "Shipped it"),
                        ),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("the trailing empty ts survives as a third routing extra") {
                    interactionPayload.type shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                    interactionPayload.routingExtras shouldBe listOf(userId, commandChannel, "")
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("the record intent asks for an ephemeral confirmation in the command channel") {
                    output.ok shouldBe true
                    val record = effects.filterIsInstance<CommandIntent.RecordStandupAnswer>().single()
                    record.responses shouldBe listOf("Shipped it")
                    record.confirmation shouldBe
                        StandupAnswerConfirmation.Ephemeral(channel = ConversationTarget(id = commandChannel))
                    effects.filterIsInstance<OutboundMessage.UpdateMessage>().shouldBeEmpty()
                }
            }
        }

        given("the decline-reason modal's private_metadata feeds the view_submission reader") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT_ROUTING"
            val noticeChannel = "C_NOTICE_DECLINE"
            val noticeMessageTs = "1700000000.000800"
            val modalJson =
                templateBuilder.declineReasonModalViewJson(
                    meetingTitle = "Project sync",
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = participantUserId,
                    noticeChannel = noticeChannel,
                    noticeMessageTs = noticeMessageTs,
                )
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = DeclineReasonModalIds.CALLBACK_ID,
                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                )

            `when`("the parser reads the writer's actual private_metadata") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("routingExtras exposes participant, notice channel, and message ts in writer order") {
                    interactionPayload.type shouldBe CommandDetailType.MEETING_DECLINE_REASON
                    interactionPayload.idempotencyKey shouldBe meetingKey.toString()
                    interactionPayload.routingExtras shouldBe
                        listOf(participantUserId, noticeChannel, noticeMessageTs)
                }
            }

            `when`("the interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("UpdateMessage collapses the notice DM at the recovered channel and ts") {
                    output.ok shouldBe true
                    val update = effects.filterIsInstance<OutboundMessage.UpdateMessage>().single()
                    update.ref.conversation.id shouldBe noticeChannel
                    update.ref.messageId shouldBe noticeMessageTs
                    update.detailType shouldBe CommandDetailType.MEETING_DECLINE_REASON
                }
            }
        }

        given("a reschedule or add-participant modal opened from a /meetup list row") {
            val meetingUid = UUID.randomUUID()
            val originChannel = "C_ORIGIN_LIST"
            val listResponseUrl = "https://hooks.slack.com/actions/T0001/123/a,b%2Fc"

            `when`("the reschedule modal carries the list's response URL and is submitted with a new time") {
                val modalJson =
                    templateBuilder.rescheduleMeetingModalViewJson(
                        meetingUid = meetingUid,
                        currentStartAt = LocalDateTime.of(2099, 7, 1, 14, 30),
                        requesterId = "U_HOST",
                        channel = originChannel,
                        listResponseUrl = listResponseUrl,
                    )
                val privateMetadata = extractPrivateMetadata(modalViewJson = modalJson)
                val submissionPayload =
                    createRoutingOnlyViewSubmissionJson(
                        callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                        privateMetadata = privateMetadata,
                        stateValues =
                            stateValuesJson(
                                "reschedule_date_block" to datepickerStateJson(selectedDate = "2099-07-10"),
                                "reschedule_time_block" to timepickerStateJson(selectedTime = "15:45"),
                            ),
                    )
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("the URL is one encoded fifth token, and the origin channel still comes from the second extra") {
                    privateMetadata.split(",").size shouldBe 5
                    interactionPayload.routingExtras shouldBe listOf("U_HOST", originChannel, listResponseUrl)
                    interactionPayload.channel.id shouldBe originChannel
                }

                then("the reschedule intent carries the URL back as the handle that closes the list") {
                    output.ok shouldBe true
                    effects.filterIsInstance<CommandIntent.RescheduleMeeting>().single().listHandle shouldBe
                        ResponseReplaceHandle(raw = listResponseUrl)
                }

                then("printing the open-view event that carries the modal names its payload but hides the view JSON") {
                    val encodedHandle = privateMetadata.split(",")[4]
                    modalJson shouldContain encodedHandle
                    val printed = createOpenViewEvent(viewJson = modalJson).toString()
                    printed shouldContain "OpenViewPayloadContents("
                    printed shouldContain "viewJson=redacted"
                    printed shouldNotContain encodedHandle
                }

                then("printing the parsed payload never shows the URL, raw or as the encoded metadata token") {
                    val printed = interactionPayload.toString()
                    printed shouldStartWith "InteractionPayload("
                    printed shouldNotContain listResponseUrl
                    printed shouldNotContain privateMetadata.split(",")[4]
                }

                then("printing the inbound interaction or its submission names them but never shows the URL") {
                    val inbound = interactionPayload.toInbound()
                    inbound.routingExtras shouldBe listOf("U_HOST", originChannel)
                    inbound.toString() shouldStartWith "InboundInteraction("
                    inbound.toString() shouldNotContain listResponseUrl
                    val submission = inbound.submission.shouldBeInstanceOf<InboundSubmission.RescheduleMeeting>()
                    submission.toString() shouldStartWith "RescheduleMeeting("
                    submission.toString() shouldNotContain listResponseUrl
                }
            }

            `when`("the add-participant modal carries the list's response URL") {
                val modalJson =
                    templateBuilder.addParticipantModalViewJson(
                        meetingUid = meetingUid,
                        requesterId = "U_HOST",
                        channel = originChannel,
                        listResponseUrl = listResponseUrl,
                    )
                val submission =
                    parser
                        .parseStringPayload(
                            payload =
                                createRoutingOnlyViewSubmissionJson(
                                    callbackId = AddParticipantModalIds.CALLBACK_ID,
                                    privateMetadata = extractPrivateMetadata(modalViewJson = modalJson),
                                ),
                        ).toInbound()
                        .submission

                then("the submission hands the decoded URL to the domain as a handle that does not print it") {
                    val addParticipant = submission.shouldBeInstanceOf<InboundSubmission.AddParticipant>()
                    addParticipant.listHandle.shouldNotBeNull().raw shouldBe listResponseUrl
                    addParticipant.toString() shouldStartWith "AddParticipant("
                    addParticipant.toString() shouldNotContain listResponseUrl
                }
            }

            `when`("a modal opened before this change submits its four-token private_metadata") {
                val submission =
                    parser
                        .parseStringPayload(
                            payload =
                                createRoutingOnlyViewSubmissionJson(
                                    callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                                    privateMetadata =
                                        "$meetingUid,${CommandDetailType.MEETING_RESCHEDULE_SUBMIT.name}," +
                                            "U_HOST,$originChannel",
                                ),
                        ).toInbound()
                        .submission

                then("there is no list to close, so the host gets the ephemeral reply as before") {
                    submission.shouldBeInstanceOf<InboundSubmission.RescheduleMeeting>().listHandle.shouldBeNull()
                }
            }
        }

        given("the /meetup list's Reschedule button opens the reschedule modal on the meeting's start") {
            val meetingUid = UUID.randomUUID()
            val meetingStartAt = LocalDateTime.of(2099, 11, 3, 10, 0)
            val list =
                templateBuilder.meetingListFormTemplate(
                    meetings =
                        listOf(
                            createMeetingDto(meetingUid = meetingUid, creator = TEST_USER_ID, startAt = meetingStartAt),
                        ),
                    currentUserId = TEST_USER_ID,
                    listIdempotencyKey = UUID.randomUUID(),
                )
            val rescheduleButtonValue =
                list.template
                    .filterIsInstance<ActionsBlock>()
                    .single()
                    .elements
                    .filterIsInstance<ButtonElement>()
                    .single { it.actionId == "${MeetingActionIds.RESCHEDULE_ACTION_ID}_$meetingUid" }
                    .value
            val stager =
                SlackOutboundStager(
                    slackEventBuilder =
                        SlackApiEventConstructor(botToken = TEST_BOT_TOKEN, templateBuilder = templateBuilder),
                    standupRepository = mockk(),
                )

            `when`("the click runs through the real parser, domain command and stager") {
                val (_, effects) =
                    runThroughDomain(
                        viewSubmissionPayload =
                            createBlockActionPayloadJson(
                                isEphemeral = true,
                                buttonType = ButtonType.PRIMARY,
                                buttonValue = rescheduleButtonValue,
                            ),
                    )
                val open = effects.filterIsInstance<OutboundMessage.OpenModal>().single()
                val modalJson =
                    stager
                        .stage(message = open, basicInfo = createCommandBasicInfo())
                        .shouldBeInstanceOf<OpenViewEvent>()
                        .payload
                        .viewJson

                then("the form carries the meeting's start and the pickers open on it, not on today") {
                    open.form.shouldBeInstanceOf<ModalForm.Reschedule>().currentStartAt shouldBe meetingStartAt
                    modalJson shouldContain "\"initial_date\":\"2099-11-03\""
                    modalJson shouldContain "\"initial_time\":\"10:00\""
                }
            }
        }

        given("a reschedule private_metadata whose routing tokens were reordered") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_SWAPPED"
            val originChannel = "C_ORIGIN_SWAPPED"
            val modalJson =
                templateBuilder.rescheduleMeetingModalViewJson(
                    meetingUid = meetingUid,
                    currentStartAt = LocalDateTime.of(2026, 7, 1, 14, 30),
                    requesterId = requesterId,
                    channel = originChannel,
                    listResponseUrl = "",
                )
            val swappedMetadata =
                extractPrivateMetadata(modalViewJson = modalJson)
                    .split(",")
                    .map { it.trim() }
                    .let { tokens -> tokens.take(2) + tokens.drop(2).reversed() }
                    .joinToString(separator = ",")
            val submissionPayload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                    privateMetadata = swappedMetadata,
                    stateValues =
                        stateValuesJson(
                            "reschedule_date_block" to datepickerStateJson(selectedDate = "2099-07-10"),
                            "reschedule_time_block" to timepickerStateJson(selectedTime = "15:45"),
                        ),
                )

            `when`("the parser reads the reordered token string") {
                val interactionPayload = parser.parseStringPayload(payload = submissionPayload)

                then("the recovered channel is the misrouted requester id, not the origin channel") {
                    interactionPayload.channel.id shouldNotBe originChannel
                    interactionPayload.channel.id shouldBe requesterId
                }
            }

            `when`("the reordered interaction is routed through the real domain command pipeline") {
                val (output, effects) = runThroughDomain(viewSubmissionPayload = submissionPayload)

                then("the real reschedule path executes and every routed slot is swapped") {
                    output.ok shouldBe true
                    val reschedule = effects.filterIsInstance<CommandIntent.RescheduleMeeting>().single()
                    reschedule.meetingUid shouldBe meetingUid
                    reschedule.newStartAt shouldBe LocalDateTime.of(2099, 7, 10, 15, 45)
                    reschedule.requesterId shouldBe originChannel
                    output.channel shouldNotBe originChannel
                    output.channel shouldBe requesterId
                }
            }
        }

        fun stateEntryOf(element: BlockElement, answer: String): Pair<String, String> =
            when (element) {
                is PlainTextInputElement ->
                    element.actionId to plainTextInputStateJson(value = answer)
                is MultiUsersSelectElement ->
                    element.actionId to multiUsersSelectStateJson(selectedUsers = answer.split(","))
                is ConversationsSelectElement ->
                    element.actionId to conversationsSelectStateJson(selectedConversation = answer)
                is TimePickerElement ->
                    element.actionId to timepickerStateJson(selectedTime = answer)
                is DatePickerElement ->
                    element.actionId to datepickerStateJson(selectedDate = answer)
                is StaticSelectElement -> {
                    element.options.map { it.value } shouldContain answer
                    element.actionId to staticSelectStateJson(value = answer)
                }
                is MultiStaticSelectElement -> {
                    val picked = answer.split(",")
                    element.options.map { it.value }.shouldContainAll(picked)
                    element.actionId to multiStaticSelectStateJson(selectedOptions = picked.map { it to it })
                }
                else -> error("no state synthesiser for element type ${element::class.simpleName}")
            }

        fun submitEmittedModal(
            modalJson: String,
            callbackId: String,
            answers: Map<String, String>,
        ): InboundInteraction {
            val view = GsonFactory.createSnakeCase().fromJson(modalJson, View::class.java)
            view.callbackId shouldBe callbackId
            val inputs = view.blocks.filterIsInstance<InputBlock>()
            inputs.map { it.blockId }.toSet() shouldBe answers.keys
            val entries =
                inputs.map { input ->
                    val (actionId, entry) =
                        stateEntryOf(element = input.element, answer = answers.getValue(input.blockId))
                    StateValueEntry(blockId = input.blockId, actionId = actionId, stateJson = entry)
                }
            val payload =
                createRoutingOnlyViewSubmissionJson(
                    callbackId = view.callbackId,
                    privateMetadata = view.privateMetadata,
                    stateValues = inputBlockStateValuesJson(entries = entries),
                )
            return parser.parseStringPayload(payload = payload).toInbound()
        }

        given("the standup-setup modal's emitted input elements are filled and read back") {
            val setupKey = UUID.randomUUID()
            val modalJson =
                templateBuilder.standupSetupModalViewJson(
                    idempotencyKey = setupKey,
                    creatorId = "U_CREATOR",
                    commandChannel = "C_COMMAND",
                )

            `when`("every emitted input block is answered according to its element type") {
                val inbound =
                    submitEmittedModal(
                        modalJson = modalJson,
                        callbackId = StandupSetupModalIds.CALLBACK_ID,
                        answers =
                            mapOf(
                                "standup_setup_name" to "daily",
                                "standup_setup_questions" to "Done?\nBlocked?",
                                "standup_setup_members" to "U_A,U_B",
                                "standup_setup_summary_channel" to "C_SUMMARY",
                                "standup_setup_weekdays" to "MONDAY,FRIDAY",
                                "standup_setup_time" to "09:15",
                                "standup_setup_cutoff" to "90",
                                "standup_setup_timezone" to "Asia/Seoul",
                            ),
                    )

                then("the parser and mapper deliver each answer to its own StandupSetup field") {
                    inbound.submission shouldBe
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
        }

        given("the add-participant modal's emitted input elements are filled and read back") {
            val meetingUid = UUID.randomUUID()
            val modalJson =
                templateBuilder.addParticipantModalViewJson(
                    meetingUid = meetingUid,
                    requesterId = "U_HOST",
                    channel = "C_ORIGIN",
                    listResponseUrl = "",
                )

            `when`("the participants are picked") {
                val inbound =
                    submitEmittedModal(
                        modalJson = modalJson,
                        callbackId = AddParticipantModalIds.CALLBACK_ID,
                        answers = mapOf("add_participant_users" to "U_NEW1,U_NEW2"),
                    )

                then("AddParticipant carries the picked users, meeting and requester") {
                    inbound.submission shouldBe
                        InboundSubmission.AddParticipant(
                            meetingUidRaw = meetingUid.toString(),
                            requesterId = "U_HOST",
                            participantUserIdsRaw = "U_NEW1,U_NEW2",
                            listHandle = null,
                        )
                }
            }
        }

        given("the decline-reason modal's emitted input elements are filled and read back") {
            val meetingKey = UUID.randomUUID()
            val modalJson =
                templateBuilder.declineReasonModalViewJson(
                    meetingTitle = "Project sync",
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = "U_GUEST",
                    noticeChannel = "D_NOTICE",
                    noticeMessageTs = "1700000000.000900",
                )

            `when`("a reason and a detail are entered") {
                val inbound =
                    submitEmittedModal(
                        modalJson = modalJson,
                        callbackId = DeclineReasonModalIds.CALLBACK_ID,
                        answers =
                            mapOf(
                                "decline_reason_block" to "OTHER",
                                "decline_reason_detail_block" to "childcare",
                            ),
                    )

                then("the select becomes the reason and the text input becomes the detail") {
                    inbound.submission shouldBe
                        InboundSubmission.DeclineReason(
                            meetingIdempotencyKeyRaw = meetingKey.toString(),
                            participantUserId = "U_GUEST",
                            noticeChannel = "D_NOTICE",
                            noticeMessageTs = "1700000000.000900",
                            reasonRaw = "OTHER",
                            detailRaw = "childcare",
                        )
                }
            }
        }

        given("the reschedule modal's emitted pickers are filled and read back") {
            val meetingUid = UUID.randomUUID()
            val modalJson =
                templateBuilder.rescheduleMeetingModalViewJson(
                    meetingUid = meetingUid,
                    currentStartAt = LocalDateTime.of(2026, 7, 1, 14, 30),
                    requesterId = "U_HOST",
                    channel = "C_ORIGIN",
                    listResponseUrl = "",
                )

            `when`("a new date and time are picked") {
                val inbound =
                    submitEmittedModal(
                        modalJson = modalJson,
                        callbackId = RescheduleMeetingModalIds.CALLBACK_ID,
                        answers =
                            mapOf(
                                "reschedule_meeting_date" to "2099-07-10",
                                "reschedule_meeting_time" to "15:45",
                            ),
                    )

                then("RescheduleMeeting carries the picked date and time") {
                    inbound.submission shouldBe
                        InboundSubmission.RescheduleMeeting(
                            meetingUidRaw = meetingUid.toString(),
                            requesterId = "U_HOST",
                            date = "2099-07-10",
                            time = "15:45",
                            listHandle = null,
                        )
                }
            }
        }
    })
