package dev.notypie.templates

import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.TopicOption
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestClientRequester.Companion.SLACK_API_BASE_URL
import dev.notypie.impl.command.RestRequester
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = KotlinLogging.logger {}

class ModalTemplateBuilder(
    private val modalBlockBuilder: ModalBlockBuilder =
        ModalBlockBuilder(),
    private val restRequester: RestRequester =
        RestClientRequester(
            baseUrl = SLACK_API_BASE_URL,
        ),
    private val slackApiToken: String,
    private val profileResolver: SlackUserProfileResolver =
        SlackUserProfileResolver(restRequester = restRequester, slackApiToken = slackApiToken),
) : SlackTemplateBuilder {
    companion object {
        const val DEFAULT_PLACEHOLDER_TEXT = "SELECT"
        private val MEETING_LIST_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val STANDUP_SESSION_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")

        const val STANDUP_SUMMARY_MAX_MEMBER_SECTIONS = 48
        private const val STANDUP_MEMBER_LINE_RESERVE: Int = 32
        private const val STANDUP_QUESTION_LINE_OVERHEAD: Int = 6
        internal const val STANDUP_ANSWER_MIN_LENGTH: Int = 50

        fun standupSummaryHeader(routineName: String, sessionDate: LocalDate): String =
            "*${routineName.escapeMrkdwn()} — ${sessionDate.format(STANDUP_SESSION_DATE_FORMAT)}*"

        fun standupSummaryMemberSection(userId: String, answer: StandupAnswerDto?, questions: List<String>): String =
            buildString {
                append("<@$userId>")
                if (answer == null) {
                    append(" _(no response)_")
                    return@buildString
                }
                questions.forEachIndexed { index, question ->
                    val response =
                        answer.responses
                            .getOrNull(index)
                            .orEmpty()
                            .ifBlank { "(blank)" }
                    append("\n• *${question.escapeMrkdwn()}* ${response.escapeMrkdwn()}")
                }
            }.truncateSectionText(limit = SlackBlockLimits.SECTION_TEXT_BUDGET)

        fun standupSummaryParts(
            routineName: String,
            sessionDate: LocalDate,
            members: List<RoutineMemberDto>,
            answers: List<StandupAnswerDto>,
            questions: List<String>,
        ): List<MessageContent.StandupSummary> {
            val answersByUser = answers.associateBy { it.userId }
            val budget =
                SlackBlockLimits.MESSAGE_TEXT_BUDGET -
                    standupSummaryHeader(routineName = "$routineName (99/99)", sessionDate = sessionDate).length
            val groups = mutableListOf<MutableList<RoutineMemberDto>>()
            var used = 0
            members.forEach { member ->
                val length =
                    standupSummaryMemberSection(
                        userId = member.userId,
                        answer = answersByUser[member.userId],
                        questions = questions,
                    ).length
                val current = groups.lastOrNull()
                if (current == null || used + length > budget || current.size >= STANDUP_SUMMARY_MAX_MEMBER_SECTIONS) {
                    groups += mutableListOf(member)
                    used = length
                } else {
                    current += member
                    used += length
                }
            }
            val pages: List<List<RoutineMemberDto>> = groups.ifEmpty { listOf(emptyList()) }
            return pages.mapIndexed { index, pageMembers ->
                MessageContent.StandupSummary(
                    routineName = if (pages.size == 1) routineName else "$routineName (${index + 1}/${pages.size})",
                    sessionDate = sessionDate,
                    members = pageMembers,
                    answers = pageMembers.mapNotNull { answersByUser[it.userId] },
                    questions = questions,
                )
            }
        }

        private fun standupAnswerMaxLength(questions: List<String>): Int {
            val fixed =
                STANDUP_MEMBER_LINE_RESERVE +
                    questions.sumOf { it.escapeMrkdwn().length + STANDUP_QUESTION_LINE_OVERHEAD }
            return ((SlackBlockLimits.SECTION_TEXT_BUDGET - fixed) / questions.size.coerceAtLeast(minimumValue = 1))
                .coerceIn(
                    minimumValue = STANDUP_ANSWER_MIN_LENGTH,
                    maximumValue = SlackBlockLimits.PLAIN_TEXT_INPUT_MAX_LENGTH,
                )
        }

        // Must stay aligned with RescheduleMeetingSubmissionContext's DATE_PATTERN/TIME_PATTERN, which reads these.
        private val RESCHEDULE_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val RESCHEDULE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm")

        // Slack caps a message at 50 blocks; worst case is 3 blocks/meeting + 2, so 3N + 2 <= 50.
        internal const val MAX_MEETINGS_PER_LIST: Int = 16
        private const val MEETING_LIST_HEADER = "My Meetings"

        // Value is the DayOfWeek enum name so the submission context round-trips via valueOf(...).
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
            modalBlockBuilder.textSections(text = message, isMarkDown = isMarkDown).forEach { add(block = it) }
        }

    override fun simpleTextResponseTemplate(headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks {
        if (headLineText.isBlank()) return onlyTextTemplate(message = body, isMarkDown = isMarkDown)
        return layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            modalBlockBuilder.textSections(text = body, isMarkDown = isMarkDown).forEach { add(block = it) }
        }
    }

    override fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks =
        layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            add(block = modalBlockBuilder.timeScheduleBlock(timeScheduleInfo = timeScheduleInfo))
        }

    override fun approvalTemplate(
        headLineText: String,
        approvalContents: ApprovalContents,
        idempotencyKey: UUID,
        commandDetailType: CommandDetailType,
    ): LayoutBlocks {
        val publisher = profileResolver.resolve(userId = approvalContents.publisherId)
        val publisherName =
            publisher.displayName.takeIf { it == "<@${approvalContents.publisherId}>" }
                ?: publisher.displayName.escapeMrkdwn()
        return layoutBlocks {
            add(block = modalBlockBuilder.headerBlock(text = headLineText))
            add(block = modalBlockBuilder.dividerBlock())
            add(
                block =
                    modalBlockBuilder.userNameWithThumbnailBlock(
                        userName = publisherName,
                        userThumbnailUrl = publisher.thumbnailUrl,
                        mkdIntroduceComment = "*Publisher* :",
                    ),
            )
            add(
                block =
                    modalBlockBuilder.textBlock(
                        "*${approvalContents.subTitle.escapeMrkdwn()}*",
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
                        "reason = $errorMessage".truncatePlainText(limit = SlackBlockLimits.SECTION_FIELD_MAX_LENGTH),
                    ),
            )
            details?.let { modalBlockBuilder.textSections(text = it, isMarkDown = false).forEach { add(block = it) } }
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
            add(block = modalBlockBuilder.headerBlock(text = MEETING_LIST_HEADER))
            add(block = modalBlockBuilder.dividerBlock())
            if (meetings.isEmpty()) {
                add(block = modalBlockBuilder.simpleText(text = "_No upcoming meetings found._", isMarkDown = true))
                return@layoutBlocks
            }
            val budget =
                SlackBlockLimits.MESSAGE_TEXT_BUDGET - MEETING_LIST_HEADER.length -
                    omittedMeetingsNotice(shown = meetings.size, total = meetings.size).length
            val sections =
                meetings.take(n = MAX_MEETINGS_PER_LIST).map { meeting ->
                    meeting to renderMeetingSection(meeting = meeting).truncateSectionText()
                }
            val totals =
                sections
                    .runningFold(initial = 0) { total, (_, text) ->
                        total + text.length + ModalBlockBuilder.HOST_MEETING_ACTIONS_TEXT_LENGTH
                    }.drop(n = 1)
            val displayed = sections.take(n = totals.count { it <= budget })
            displayed.forEachIndexed { index, (meeting, text) ->
                add(block = modalBlockBuilder.simpleText(text = text, isMarkDown = true))
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
            if (displayed.size < meetings.size) {
                add(
                    block =
                        modalBlockBuilder.simpleText(
                            text = omittedMeetingsNotice(shown = displayed.size, total = meetings.size),
                            isMarkDown = true,
                        ),
                )
            }
        }

    private fun omittedMeetingsNotice(shown: Int, total: Int): String =
        "_Showing the first $shown of $total meetings. ${total - shown} more omitted — narrow the range to see them._"

    private fun renderMeetingSection(meeting: MeetingDto): String {
        val title = meeting.title.escapeMrkdwn()
        val titleLine = if (meeting.isCanceled) "*$title* *[CANCELED]*" else "*$title*"
        val timeLine =
            buildString {
                append(meeting.startAt.format(MEETING_LIST_TIMESTAMP_FORMAT))
                meeting.endAt?.let { append(" ~ ${it.format(MEETING_LIST_TIMESTAMP_FORMAT)}") }
            }
        val totalCount = 1 + meeting.participants.size
        val acceptedCount = 1 + meeting.participants.count { it.isAttending }
        val participantsLine = "Participants: $acceptedCount/$totalCount"
        val declinedLine = renderDeclinedLine(meeting = meeting)
        val uidLine = "`${meeting.meetingUid}`"
        return listOfNotNull(titleLine, timeLine, participantsLine, declinedLine, uidLine)
            .joinToString(separator = "\n")
    }

    private fun renderDeclinedLine(meeting: MeetingDto): String? {
        val declined = meeting.participants.filter { !it.isAttending }
        if (declined.isEmpty()) return null
        return buildString {
            append("Declined:")
            declined.forEach { participant ->
                append("\n• <@${participant.userId}> — ${participant.absentReason.showMessage}")
                participant.absentReasonDetail
                    ?.takeIf { it.isNotBlank() }
                    ?.let { detail -> append(" (_${detail.escapeMrkdwn()}_)") }
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
        val view =
            modal {
                callbackId(id = DeclineReasonModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            meetingIdempotencyKey.toString(),
                            CommandDetailType.MEETING_DECLINE_REASON.name,
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
                        section { mrkdwn(text = "*${meetingTitle.escapeMrkdwn()}*") }
                    }
                    input(blockId = DeclineReasonModalIds.BLOCK_ID) {
                        label(text = "Reason")
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
                    input(blockId = DeclineReasonModalIds.DETAIL_BLOCK_ID) {
                        optional(value = true)
                        label(text = "Details (required if you pick Other)")
                        plainTextInput(
                            actionId = DeclineReasonModalIds.DETAIL_ACTION_ID,
                            multiline = true,
                            maxLength = RejectReason.MAX_DETAIL_LENGTH,
                        )
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
        val view =
            modal {
                callbackId(id = RescheduleMeetingModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            meetingUid.toString(),
                            CommandDetailType.MEETING_RESCHEDULE_SUBMIT.name,
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
        val view =
            modal {
                callbackId(id = AddParticipantModalIds.CALLBACK_ID)
                privateMetadata(
                    metadata =
                        listOf(
                            meetingUid.toString(),
                            CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT.name,
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
                        mrkdwn(
                            text = "*${routineName.escapeMrkdwn()}* — ${sessionDate.format(
                                STANDUP_SESSION_DATE_FORMAT,
                            )}",
                        )
                    }
                    val answerMaxLength = standupAnswerMaxLength(questions = questions)
                    questions.forEachIndexed { index, question ->
                        input(blockId = "${StandupModalIds.BLOCK_ID_PREFIX}$index") {
                            label(text = question)
                            plainTextInput(
                                actionId = "${StandupModalIds.ACTION_ID_PREFIX}$index",
                                multiline = true,
                                maxLength = answerMaxLength,
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
                        label(text = "Cutoff minutes (1–1440)")
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

    override fun cveSubscribeModalViewJson(idempotencyKey: UUID, topics: List<TopicOption>): String =
        cveTopicPickerModalViewJson(
            idempotencyKey = idempotencyKey,
            submitType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT,
            callbackId = CveSubscriptionModalIds.SUBSCRIBE_CALLBACK_ID,
            blockId = CveSubscriptionModalIds.SUBSCRIBE_TOPICS_BLOCK_ID,
            actionId = CveSubscriptionModalIds.SUBSCRIBE_TOPICS_ACTION_ID,
            titleText = "Subscribe",
            submitText = "Subscribe",
            topics = topics,
        )

    override fun cveUnsubscribeModalViewJson(idempotencyKey: UUID, topics: List<TopicOption>): String =
        cveTopicPickerModalViewJson(
            idempotencyKey = idempotencyKey,
            submitType = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT,
            callbackId = CveSubscriptionModalIds.UNSUBSCRIBE_CALLBACK_ID,
            blockId = CveSubscriptionModalIds.UNSUBSCRIBE_TOPICS_BLOCK_ID,
            actionId = CveSubscriptionModalIds.UNSUBSCRIBE_TOPICS_ACTION_ID,
            titleText = "Unsubscribe",
            submitText = "Unsubscribe",
            topics = topics,
        )

    private fun cveTopicPickerModalViewJson(
        idempotencyKey: UUID,
        submitType: CommandDetailType,
        callbackId: String,
        blockId: String,
        actionId: String,
        titleText: String,
        submitText: String,
        topics: List<TopicOption>,
    ): String {
        if (topics.size > SlackBlockLimits.MAX_OPTIONS) {
            log.warn {
                "CVE topic picker lists the first ${SlackBlockLimits.MAX_OPTIONS} of ${topics.size} topics: " +
                    "callbackId=$callbackId"
            }
        }
        val view =
            modal {
                callbackId(id = callbackId)
                privateMetadata(
                    metadata =
                        listOf(
                            idempotencyKey.toString(),
                            submitType.name,
                        ).joinToString(","),
                )
                title(text = titleText)
                submit(text = submitText)
                close(text = "Cancel")
                blocks {
                    input(blockId = blockId) {
                        label(text = "Topics")
                        multiStaticSelect(
                            actionId = actionId,
                            placeholder = "Select topics",
                        ) {
                            topics.take(n = SlackBlockLimits.MAX_OPTIONS).forEach { topic ->
                                val label =
                                    topic.label.truncatePlainText(
                                        limit = SlackBlockLimits.OPTION_TEXT_MAX_LENGTH,
                                    )
                                option(text = label, value = topic.key)
                            }
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
        val hiddenMembers = members.size - STANDUP_SUMMARY_MAX_MEMBER_SECTIONS
        return layoutBlocks {
            add(
                block =
                    modalBlockBuilder.simpleText(
                        text = standupSummaryHeader(routineName = routineName, sessionDate = sessionDate),
                        isMarkDown = true,
                    ),
            )
            members.take(STANDUP_SUMMARY_MAX_MEMBER_SECTIONS).forEach { member ->
                add(
                    block =
                        modalBlockBuilder.simpleText(
                            text =
                                standupSummaryMemberSection(
                                    userId = member.userId,
                                    answer = answersByUser[member.userId],
                                    questions = questions,
                                ),
                            isMarkDown = true,
                        ),
                )
            }
            if (hiddenMembers > 0) {
                add(
                    block =
                        modalBlockBuilder.simpleText(
                            text = "_…and $hiddenMembers more members_",
                            isMarkDown = true,
                        ),
                )
            }
        }
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
