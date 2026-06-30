package dev.notypie.templates

import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestClientRequester.Companion.SLACK_API_BASE_URL
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A class that implements the SlackTemplateBuilder interface and provides methods for building modal templates.
 */
class ModalTemplateBuilder(
    private val modalBlockBuilder: ModalBlockBuilder =
        ModalBlockBuilder(),
    private val restRequester: RestRequester =
        RestClientRequester(
            baseUrl = SLACK_API_BASE_URL,
        ),
    private val slackApiToken: String,
) : SlackTemplateBuilder {
    companion object {
        const val DEFAULT_PLACEHOLDER_TEXT = "SELECT"
        private val MEETING_LIST_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val STANDUP_SESSION_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // Initial-value formats for the reschedule modal's date/time pickers. The submission
        // context (RescheduleMeetingSubmissionContext) reads the selected values back with the
        // matching patterns, so these must stay aligned with its DATE_PATTERN / TIME_PATTERN.
        private val RESCHEDULE_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val RESCHEDULE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm")

        // Slack caps a message at 50 blocks. Worst case is 3 blocks per meeting (section +
        // host-actions + divider) plus header, top divider, and truncation notice: 3N + 2 <= 50,
        // so N <= 16.
        internal const val MAX_MEETINGS_PER_LIST: Int = 16

        // Mon–Sun options for the standup-setup weekday multi-select; value is the DayOfWeek
        // enum name so the submission context can round-trip via DayOfWeek.valueOf(...).
        private val WEEKDAY_OPTIONS: List<Pair<DayOfWeek, String>> =
            listOf(
                DayOfWeek.MONDAY to "Monday",
                DayOfWeek.TUESDAY to "Tuesday",
                DayOfWeek.WEDNESDAY to "Wednesday",
                DayOfWeek.THURSDAY to "Thursday",
                DayOfWeek.FRIDAY to "Friday",
                DayOfWeek.SATURDAY to "Saturday",
                DayOfWeek.SUNDAY to "Sunday",
            )

        // Small curated list of common zones for the standup-setup timezone picker; value is
        // the IANA id parsed by ZoneId.of(...) in the submission context.
        private val TIMEZONE_OPTIONS: List<String> =
            listOf(
                "Asia/Seoul",
                "UTC",
                "America/Los_Angeles",
                "Europe/London",
            )

        private const val DEFAULT_TRIGGER_TIME: String = "10:00"
        private const val DEFAULT_CUTOFF_MINUTES: String = "120"
    }

    override fun onlyTextTemplate(message: String, isMarkDown: Boolean): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.simpleText(text = message, isMarkDown = isMarkDown))
        }

    override fun simpleTextResponseTemplate(headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            add(block = modalBlockBuilder.simpleText(text = body, isMarkDown = isMarkDown))
        }

    override fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            add(block = modalBlockBuilder.timeScheduleBlock(timeScheduleInfo = timeScheduleInfo))
        }

    // Username with thumbnail Requires Role users.profile.get. Reference from https://api.slack.com/methods/users.profile.get
    override fun approvalTemplate(
        headLineText: String,
        approvalContents: ApprovalContents,
        idempotencyKey: UUID,
        commandDetailType: CommandDetailType,
    ): LayoutBlocks {
        val user =
            restRequester.get(
                uri = "users.profile.get?user=${approvalContents.publisherId}",
                authorizationHeader = slackApiToken,
                responseType = SlackUserProfileDto::class.java,
            )
        return layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            add(
                block =
                    modalBlockBuilder.userNameWithThumbnailBlock(
                        userName = user.profile.displayName,
                        userThumbnailUrl = user.profile.imageSize24,
                        mkdIntroduceComment = "*Publisher* :",
                    ),
            )
            add(
                block =
                    modalBlockBuilder.textBlock(
                        "*${approvalContents.subTitle}*",
                        isMarkDown = true,
                    ),
            )
            add(layout = modalBlockBuilder.approvalBlock(approvalContents = approvalContents))
        }
    }

    override fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            add(
                block =
                    modalBlockBuilder.textBlock(
                        "type = exception",
                        "reason = $errorMessage",
                    ),
            )
            details?.let { add(block = modalBlockBuilder.simpleText(text = it, isMarkDown = false)) }
        }

    override fun requestApprovalFormTemplate(
        headLineText: String,
        selectionFields: List<SelectionContents>,
        approvalContents: ApprovalContents,
        approvalTargetUser: MultiUserSelectContents?,
        reasonInput: TextInputContents?,
    ): LayoutBlocks {
        val targetUser =
            approvalTargetUser
                ?: MultiUserSelectContents(
                    title = "Select target user",
                    placeholderText = DEFAULT_PLACEHOLDER_TEXT,
                )
        val selectionLayouts =
            selectionFields.map { modalBlockBuilder.selectionBlock(selectionContents = it) }

        return layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            addAll(layouts = selectionLayouts)
            add(layout = modalBlockBuilder.multiUserSelectBlock(contents = targetUser))
            reasonInput?.let { add(block = modalBlockBuilder.plainTextInputBlock(contents = it)) }
            add(layout = modalBlockBuilder.approvalBlock(approvalContents = approvalContents))
        }
    }

    override fun meetingListFormTemplate(
        meetings: List<MeetingDto>,
        currentUserId: String,
        listIdempotencyKey: UUID,
    ): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = "My Meetings"))
            add(block = modalBlockBuilder.dividerBlock())
            if (meetings.isEmpty()) {
                add(block = modalBlockBuilder.simpleText(text = "_No upcoming meetings found._", isMarkDown = true))
                return@layoutBlocks
            }
            val displayed = meetings.take(n = MAX_MEETINGS_PER_LIST)
            displayed.forEachIndexed { index, meeting ->
                add(
                    block =
                        modalBlockBuilder.simpleText(
                            text = renderMeetingSection(meeting = meeting),
                            isMarkDown = true,
                        ),
                )
                if (meeting.creator == currentUserId && !meeting.isCanceled) {
                    add(
                        layout =
                            modalBlockBuilder.hostMeetingActionsBlock(
                                meetingUid = meeting.meetingUid,
                                listIdempotencyKey = listIdempotencyKey,
                            ),
                    )
                }
                if (index != displayed.lastIndex) add(block = modalBlockBuilder.dividerBlock())
            }
            if (meetings.size > MAX_MEETINGS_PER_LIST) {
                val hidden = meetings.size - MAX_MEETINGS_PER_LIST
                // No preceding divider: the italic notice is visually distinct, and skipping
                // the divider keeps worst-case total at 3*MAX+2 = 50 blocks (Slack's cap).
                add(
                    block =
                        modalBlockBuilder.simpleText(
                            text =
                                "_Showing the first $MAX_MEETINGS_PER_LIST of ${meetings.size} meetings. " +
                                    "$hidden more omitted — narrow the range to see them._",
                            isMarkDown = true,
                        ),
                )
            }
        }

    private fun renderMeetingSection(meeting: MeetingDto): String {
        val titleLine =
            if (meeting.isCanceled) {
                "*${meeting.title}* *[CANCELED]*"
            } else {
                "*${meeting.title}*"
            }
        val timeLine =
            buildString {
                append(meeting.startAt.format(MEETING_LIST_TIMESTAMP_FORMAT))
                meeting.endAt?.let { append(" ~ ${it.format(MEETING_LIST_TIMESTAMP_FORMAT)}") }
            }
        // Host is always counted as attending; invitees contribute to the denominator in full
        // and to the numerator only while `isAttending` remains true (decliners subtract out).
        val totalCount = 1 + meeting.participants.size
        val acceptedCount = 1 + meeting.participants.count { it.isAttending }
        val participantsLine = "Participants: $acceptedCount/$totalCount"
        val declinedLine = renderDeclinedLine(meeting = meeting)
        val uidLine = "`${meeting.meetingUid}`"
        return listOfNotNull(titleLine, timeLine, participantsLine, declinedLine, uidLine)
            .joinToString(separator = "\n")
    }

    /**
     * Lists everyone who declined as Slack mentions with their reason (and the free-text detail
     * for OTHER), one per line. Returns null when nobody has declined so the section stays compact.
     */
    private fun renderDeclinedLine(meeting: MeetingDto): String? {
        val declined = meeting.participants.filter { !it.isAttending }
        if (declined.isEmpty()) return null
        return buildString {
            append("Declined:")
            declined.forEach { participant ->
                append("\n• <@${participant.userId}> — ${participant.absentReason.showMessage}")
                participant.absentReasonDetail
                    ?.takeIf { it.isNotBlank() }
                    ?.let { detail -> append(" (_${detail}_)") }
            }
        }
    }

    override fun requestMeetingFormTemplate(approvalContents: ApprovalContents): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = "Create new meeting"))
            add(block = modalBlockBuilder.dividerBlock())
            add(
                block =
                    modalBlockBuilder.calendarThumbnailBlock(
                        title = "Schedule a new meeting",
                        markdownBody =
                            "Create a new meeting.\n " +
                                "Please choose the meeting participants and the meeting date.",
                    ),
            )
            add(
                block =
                    modalBlockBuilder.plainTextInputBlock(
                        contents =
                            TextInputContents(
                                title = "Meeting name",
                                placeholderText = "Meeting name",
                            ),
                    ),
            )
            add(
                block =
                    modalBlockBuilder.plainTextInputBlock(
                        contents =
                            TextInputContents(
                                title = "Reason",
                                placeholderText = "Reason",
                            ),
                    ),
            )
            add(
                layout =
                    modalBlockBuilder.checkBoxesBlock(
                        CheckBoxOptions(
                            text = "*Confirmation CallBack*",
                            description = "Send confirmation request to all participants and receive result",
                        ),
                    ),
            )
            add(
                layout =
                    modalBlockBuilder.multiUserSelectBlock(
                        contents =
                            MultiUserSelectContents(
                                title = "Select meeting members",
                                placeholderText = DEFAULT_PLACEHOLDER_TEXT,
                            ),
                    ),
            )
            add(block = modalBlockBuilder.simpleText(text = "Select meetup time", isMarkDown = false))
            add(layout = modalBlockBuilder.selectDateTimeScheduleBlock())
            add(layout = modalBlockBuilder.approvalBlock(approvalContents = approvalContents))
        }

    override fun declineReasonModalViewJson(
        meetingTitle: String,
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        noticeChannel: String,
        noticeMessageTs: String,
    ): String {
        // Token order must match SlackInteractionRequestParser: idempotencyKey, detailType,
        // then routingExtras[0..n]. DeclineReasonSubmissionContext reads routingExtras[0] as
        // participantUserId, [1] as noticeChannel, [2] as noticeMessageTs. Extras are
        // URL-decoded on the parser side; we emit Slack IDs (URL-safe ASCII) raw, which is a
        // no-op for URL-decode. Blank channel/ts still occupy a position so indices stay stable.
        val view =
            modal {
                callbackId(id = DeclineReasonModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            meetingIdempotencyKey.toString(),
                            CommandDetailType.DECLINE_REASON_MODAL.name,
                            participantUserId,
                            noticeChannel,
                            noticeMessageTs,
                        ).joinToString(","),
                )
                title(text = "Why can't you attend?")
                submit(text = "Submit")
                close(text = "Cancel")
                blocks {
                    if (meetingTitle.isNotBlank()) {
                        section { mrkdwn(text = "*$meetingTitle*") }
                    }
                    input(blockId = DeclineReasonModalIds.BLOCK_ID) {
                        label(text = "Reason")
                        // static_select (dropdown) scales better than radio_buttons for 8
                        // options — radios stack vertically and push the Submit button
                        // below the fold on narrower clients.
                        staticSelect(
                            actionId = DeclineReasonModalIds.ACTION_ID,
                            placeholder = "Pick a reason",
                        ) {
                            RejectReason.entries
                                .filter { it != RejectReason.ATTENDING }
                                .forEach { reason ->
                                    option(text = reason.showMessage, value = reason.name)
                                }
                        }
                    }
                    // Optional in Slack so non-Other reasons submit without text; the
                    // "required when Other" rule is enforced on submit via response_action errors
                    // (DeclineReasonSubmissionContext / SlackInteractionHandlerImpl).
                    input(blockId = DeclineReasonModalIds.DETAIL_BLOCK_ID) {
                        optional(value = true)
                        label(text = "Details (required if you pick Other)")
                        plainTextInput(actionId = DeclineReasonModalIds.DETAIL_ACTION_ID, multiline = true)
                    }
                }
            }
        return jsonMapper.writeValueAsString(view)
    }

    override fun rescheduleMeetingModalViewJson(
        meetingUid: UUID,
        currentStartAt: LocalDateTime,
        requesterId: String,
        channel: String,
    ): String {
        // Token order must match SlackInteractionRequestParser: idempotencyKey (the meetingUid),
        // detailType, then routingExtras[0..n]. RescheduleMeetingSubmissionContext reads
        // routingExtras[0] as requesterId and routingExtras[1] as the originating channel. The pickers
        // are pre-filled with the meeting's current start so a host only changes the part that moved.
        val view =
            modal {
                callbackId(id = RescheduleMeetingModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            meetingUid.toString(),
                            CommandDetailType.RESCHEDULE_MEETING_SUBMIT.name,
                            requesterId,
                            channel,
                        ).joinToString(","),
                )
                title(text = "Reschedule meeting")
                submit(text = "Reschedule")
                close(text = "Cancel")
                blocks {
                    input(blockId = RescheduleMeetingModalIds.DATE_BLOCK_ID) {
                        label(text = "New date")
                        datePicker(
                            actionId = RescheduleMeetingModalIds.DATE_ACTION_ID,
                            initialDate = currentStartAt.format(RESCHEDULE_DATE_FORMAT),
                        )
                    }
                    input(blockId = RescheduleMeetingModalIds.TIME_BLOCK_ID) {
                        label(text = "New time")
                        timePicker(
                            actionId = RescheduleMeetingModalIds.TIME_ACTION_ID,
                            initialTime = currentStartAt.format(RESCHEDULE_TIME_FORMAT),
                        )
                    }
                }
            }
        return jsonMapper.writeValueAsString(view)
    }

    override fun addParticipantModalViewJson(meetingUid: UUID, requesterId: String, channel: String): String {
        // Token order must match SlackInteractionRequestParser: idempotencyKey (the meetingUid),
        // detailType, then routingExtras[0..n]. AddParticipantSubmissionContext reads routingExtras[0]
        // as requesterId, routingExtras[1] as the originating channel, and the multi-users select
        // (by block id) as the user ids to add.
        val view =
            modal {
                callbackId(id = AddParticipantModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            meetingUid.toString(),
                            CommandDetailType.ADD_PARTICIPANT_SUBMIT.name,
                            requesterId,
                            channel,
                        ).joinToString(","),
                )
                title(text = "Add participants")
                submit(text = "Add")
                close(text = "Cancel")
                blocks {
                    input(blockId = AddParticipantModalIds.USERS_BLOCK_ID) {
                        label(text = "Participants to add")
                        multiUsersSelect(
                            actionId = AddParticipantModalIds.USERS_ACTION_ID,
                            placeholder = "Select people",
                        )
                    }
                }
            }
        return jsonMapper.writeValueAsString(view)
    }

    override fun standupModalViewJson(
        routineName: String,
        sessionDate: LocalDate,
        sessionUid: UUID,
        userId: String,
        noticeChannel: String,
        noticeMessageTs: String,
        questions: List<String>,
    ): String {
        val view =
            modal {
                callbackId(id = StandupModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            sessionUid.toString(),
                            CommandDetailType.STANDUP_ANSWER_SUBMIT.name,
                            userId,
                            noticeChannel,
                            noticeMessageTs,
                        ).joinToString(","),
                )
                title(text = "Standup")
                submit(text = "Submit")
                close(text = "Cancel")
                blocks {
                    section {
                        mrkdwn(text = "*$routineName* — ${sessionDate.format(STANDUP_SESSION_DATE_FORMAT)}")
                    }
                    questions.forEachIndexed { index, question ->
                        input(blockId = "${StandupModalIds.BLOCK_ID_PREFIX}$index") {
                            label(text = question)
                            plainTextInput(
                                actionId = "${StandupModalIds.ACTION_ID_PREFIX}$index",
                                multiline = true,
                            )
                        }
                    }
                }
            }
        return jsonMapper.writeValueAsString(view)
    }

    override fun standupSetupModalViewJson(idempotencyKey: UUID, creatorId: String, commandChannel: String): String {
        val view =
            modal {
                callbackId(id = StandupSetupModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            idempotencyKey.toString(),
                            CommandDetailType.STANDUP_SETUP_SUBMIT.name,
                            creatorId,
                            commandChannel,
                        ).joinToString(","),
                )
                title(text = "Standup setup")
                submit(text = "Create")
                close(text = "Cancel")
                blocks {
                    input(blockId = StandupSetupModalIds.NAME_BLOCK_ID) {
                        label(text = "Routine name")
                        plainTextInput(actionId = StandupSetupModalIds.NAME_ACTION_ID)
                    }
                    input(blockId = StandupSetupModalIds.QUESTIONS_BLOCK_ID) {
                        label(text = "Questions (one per line)")
                        plainTextInput(actionId = StandupSetupModalIds.QUESTIONS_ACTION_ID, multiline = true)
                    }
                    input(blockId = StandupSetupModalIds.MEMBERS_BLOCK_ID) {
                        label(text = "Members")
                        multiUsersSelect(
                            actionId = StandupSetupModalIds.MEMBERS_ACTION_ID,
                            placeholder = "Select members",
                        )
                    }
                    input(blockId = StandupSetupModalIds.SUMMARY_CHANNEL_BLOCK_ID) {
                        label(text = "Summary channel")
                        conversationsSelect(
                            actionId = StandupSetupModalIds.SUMMARY_CHANNEL_ACTION_ID,
                            placeholder = "Select a channel",
                        )
                    }
                    input(blockId = StandupSetupModalIds.WEEKDAYS_BLOCK_ID) {
                        label(text = "Active weekdays")
                        multiStaticSelect(
                            actionId = StandupSetupModalIds.WEEKDAYS_ACTION_ID,
                            placeholder = "Select weekdays",
                        ) {
                            WEEKDAY_OPTIONS.forEach { (day, displayName) ->
                                option(text = displayName, value = day.name)
                            }
                        }
                    }
                    input(blockId = StandupSetupModalIds.TIME_BLOCK_ID) {
                        label(text = "Trigger time")
                        timePicker(
                            actionId = StandupSetupModalIds.TIME_ACTION_ID,
                            initialTime = DEFAULT_TRIGGER_TIME,
                        )
                    }
                    input(blockId = StandupSetupModalIds.CUTOFF_BLOCK_ID) {
                        label(text = "Cutoff minutes")
                        plainTextInput(
                            actionId = StandupSetupModalIds.CUTOFF_ACTION_ID,
                            initialValue = DEFAULT_CUTOFF_MINUTES,
                        )
                    }
                    input(blockId = StandupSetupModalIds.TIMEZONE_BLOCK_ID) {
                        label(text = "Timezone")
                        staticSelect(
                            actionId = StandupSetupModalIds.TIMEZONE_ACTION_ID,
                            placeholder = "Select a timezone",
                        ) {
                            TIMEZONE_OPTIONS.forEach { zone -> option(text = zone, value = zone) }
                        }
                    }
                }
            }
        return jsonMapper.writeValueAsString(view)
    }

    override fun standupSummaryTemplate(
        routineName: String,
        sessionDate: LocalDate,
        members: List<RoutineMemberDto>,
        answers: List<StandupAnswerDto>,
        questions: List<String>,
    ): LayoutBlocks {
        val answersByUser = answers.associateBy { it.userId }
        val body =
            buildString {
                append("*$routineName — ${sessionDate.format(STANDUP_SESSION_DATE_FORMAT)}*")
                members.forEach { member ->
                    append("\n\n<@${member.userId}>")
                    val answer = answersByUser[member.userId]
                    if (answer == null) {
                        append(" _(no response)_")
                    } else {
                        questions.forEachIndexed { index, question ->
                            val response =
                                answer.responses
                                    .getOrNull(index)
                                    .orEmpty()
                                    .ifBlank { "(blank)" }
                            append("\n• *$question* $response")
                        }
                    }
                }
            }
        return onlyTextTemplate(message = body, isMarkDown = true)
    }

    override fun timeScheduleNoticeTemplate(
        timeScheduleInfo: TimeScheduleAlertContents,
        approvalContents: ApprovalContents,
    ): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = "Time Schedule Notice"))
            add(block = modalBlockBuilder.dividerBlock())
            add(
                block =
                    modalBlockBuilder.calendarThumbnailBlock(
                        title = timeScheduleInfo.title,
                        markdownBody = timeScheduleInfo.description,
                    ),
            )
            add(
                layout =
                    modalBlockBuilder.radioButtonBlock(
                        *timeScheduleInfo.rejectReasons.toTypedArray(),
                        description = "Capturing reasons for meeting absence",
                    ),
            )
            add(
                block =
                    modalBlockBuilder.plainTextInputBlock(
                        contents = TextInputContents("detail reason", ""),
                    ),
            )
            add(layout = modalBlockBuilder.approvalBlock(approvalContents = approvalContents))
        }
}
