package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.interactions.States
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
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import java.time.LocalDate
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

        /**
         * Slack Block Kit caps each message at 50 blocks. Worst case (every meeting hosted
         * by the current user → every row gets a Cancel actions block) is:
         *   header(1) + top-divider(1) + N sections + N cancel-actions + (N-1) inter-dividers
         *     = 3N + 1
         * Plus the truncation notice (1 block, no preceding divider — it's italic and
         * visually distinct) brings the worst case to 3N + 2.
         * For 50-block safety we keep 3N + 2 <= 50, i.e. N <= 16.
         */
        internal const val MAX_MEETINGS_PER_LIST: Int = 16
    }

    private fun toLayoutBlocks(vararg blocks: LayoutBlock, states: List<States> = listOf()) =
        LayoutBlocks(
            interactionStates = states,
            template = blocks.toList(),
        )

    override fun onlyTextTemplate(message: String, isMarkDown: Boolean): LayoutBlocks =
        toLayoutBlocks(
            modalBlockBuilder.simpleText(
                text = message,
                isMarkDown = isMarkDown,
            ),
        )

    override fun simpleTextResponseTemplate(headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks =
        toLayoutBlocks(
            modalBlockBuilder.headerBlock(
                text = headLineText,
            ),
            modalBlockBuilder.dividerBlock(),
            modalBlockBuilder.simpleText(
                text = body,
                isMarkDown = isMarkDown,
            ),
        )

    override fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks =
        toLayoutBlocks(
            modalBlockBuilder.headerBlock(
                text = headLineText,
            ),
            modalBlockBuilder.dividerBlock(),
            modalBlockBuilder.timeScheduleBlock(
                timeScheduleInfo = timeScheduleInfo,
            ),
        )

    // Username with thumbnail Requires Role users.profile.get. Reference from https://api.slack.com/methods/users.profile.get
    override fun approvalTemplate(
        headLineText: String,
        approvalContents: ApprovalContents,
        idempotencyKey: UUID,
        commandDetailType: CommandDetailType,
    ): LayoutBlocks {
        val buttonLayout =
            modalBlockBuilder.approvalBlock(
                approvalContents = approvalContents,
            )
        val user =
            restRequester.get(
                uri = "users.profile.get?user=${approvalContents.publisherId}",
                authorizationHeader = slackApiToken,
                responseType = SlackUserProfileDto::class.java,
            )
        return toLayoutBlocks(
            modalBlockBuilder.headerBlock(
                text = headLineText,
            ),
            modalBlockBuilder.dividerBlock(),
            modalBlockBuilder
                .userNameWithThumbnailBlock(
                    userName = user.profile.displayName,
                    userThumbnailUrl = user.profile.imageSize24,
                    mkdIntroduceComment = "*Publisher* :",
                ),
            modalBlockBuilder.textBlock(
                "*${approvalContents.subTitle}*",
                isMarkDown = true,
            ),
            buttonLayout.layout,
            states = buttonLayout.interactiveObjects,
        )
    }

    override fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks {
        val blocks =
            mutableListOf(
                modalBlockBuilder.headerBlock(
                    text = headLineText,
                ),
                modalBlockBuilder.dividerBlock(),
                modalBlockBuilder.textBlock(
                    "type = exception",
                    "reason = $errorMessage",
                ),
            )
        details?.let {
            blocks.add(
                modalBlockBuilder.simpleText(
                    text = it,
                    isMarkDown = false,
                ),
            )
        }
        return toLayoutBlocks(*blocks.toTypedArray())
    }

    override fun requestApprovalFormTemplate(
        headLineText: String,
        selectionFields: List<SelectionContents>,
        approvalContents: ApprovalContents,
        approvalTargetUser: MultiUserSelectContents?,
        reasonInput: TextInputContents?,
    ): LayoutBlocks {
        val approvalLayout =
            modalBlockBuilder.approvalBlock(
                approvalContents = approvalContents,
            )
        val userSelectLayout =
            modalBlockBuilder.multiUserSelectBlock(
                contents =
                    approvalTargetUser
                        ?: MultiUserSelectContents(
                            title = "Select target user",
                            placeholderText = DEFAULT_PLACEHOLDER_TEXT,
                        ),
            )

        val selectionLayouts: List<InteractionLayoutBlock> =
            selectionFields.map {
                modalBlockBuilder.selectionBlock(
                    selectionContents = it,
                )
            }

        val blocks =
            mutableListOf(
                modalBlockBuilder.headerBlock(
                    text = headLineText,
                ),
                modalBlockBuilder.dividerBlock(),
            ).apply {
                addAll(selectionLayouts.map { it.layout })
                add(userSelectLayout.layout)
                reasonInput?.let {
                    add(
                        modalBlockBuilder
                            .plainTextInputBlock(
                                contents = reasonInput,
                            ),
                    )
                }
                add(approvalLayout.layout)
            }

        val states =
            userSelectLayout.interactiveObjects +
                selectionLayouts.flatMap { it.interactiveObjects } +
                approvalLayout.interactiveObjects

        return toLayoutBlocks(
            *blocks.toTypedArray(),
            states = states,
        )
    }

    override fun meetingListFormTemplate(
        meetings: List<MeetingDto>,
        currentUserId: String,
        listIdempotencyKey: UUID,
    ): LayoutBlocks {
        val blocks =
            mutableListOf<LayoutBlock>(
                modalBlockBuilder.headerBlock(text = "My Meetings"),
                modalBlockBuilder.dividerBlock(),
            )
        if (meetings.isEmpty()) {
            blocks.add(
                modalBlockBuilder.simpleText(
                    text = "_No upcoming meetings found._",
                    isMarkDown = true,
                ),
            )
            return toLayoutBlocks(*blocks.toTypedArray())
        }
        val cancelStates = mutableListOf<States>()
        val displayed = meetings.take(n = MAX_MEETINGS_PER_LIST)
        displayed.forEachIndexed { index, meeting ->
            blocks.add(
                modalBlockBuilder.simpleText(
                    text = renderMeetingSection(meeting = meeting),
                    isMarkDown = true,
                ),
            )
            if (meeting.creator == currentUserId && !meeting.isCanceled) {
                val cancelLayout =
                    modalBlockBuilder.cancelMeetingActionsBlock(
                        meetingUid = meeting.meetingUid,
                        listIdempotencyKey = listIdempotencyKey,
                    )
                blocks.add(cancelLayout.layout)
                cancelStates.addAll(cancelLayout.interactiveObjects)
            }
            if (index != displayed.lastIndex) {
                blocks.add(modalBlockBuilder.dividerBlock())
            }
        }
        if (meetings.size > MAX_MEETINGS_PER_LIST) {
            val hidden = meetings.size - MAX_MEETINGS_PER_LIST
            val notice =
                "_Showing the first $MAX_MEETINGS_PER_LIST of ${meetings.size} meetings. " +
                    "$hidden more omitted — narrow the range to see them._"
            // No preceding divider: the italic notice is visually distinct, and skipping
            // the divider keeps worst-case total at 3*MAX+2 = 50 blocks (Slack's cap).
            blocks.add(modalBlockBuilder.simpleText(text = notice, isMarkDown = true))
        }
        return toLayoutBlocks(*blocks.toTypedArray(), states = cancelStates)
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
        val uidLine = "`${meeting.meetingUid}`"
        return "$titleLine\n$timeLine\n$participantsLine\n$uidLine"
    }

    override fun requestMeetingFormTemplate(approvalContents: ApprovalContents): LayoutBlocks {
        val callbackCheckboxes =
            modalBlockBuilder.checkBoxesBlock(
                CheckBoxOptions(
                    text = "*Confirmation CallBack*",
                    description = "Send confirmation request to all participants and receive result",
                ),
            )
        val multiUserSelectionContents =
            modalBlockBuilder.multiUserSelectBlock(
                contents =
                    MultiUserSelectContents(
                        title = "Select meeting members",
                        placeholderText = DEFAULT_PLACEHOLDER_TEXT,
                    ),
            )
        val timeScheduleBlock =
            modalBlockBuilder
                .selectDateTimeScheduleBlock()
        val approvalLayout =
            modalBlockBuilder.approvalBlock(
                approvalContents = approvalContents,
            )

        val blocks =
            listOf(
                modalBlockBuilder.headerBlock(
                    text = "Create new meeting",
                ),
                modalBlockBuilder.dividerBlock(),
                modalBlockBuilder
                    .calendarThumbnailBlock(
                        title = "Schedule a new meeting",
                        markdownBody =
                            "Create a new meeting.\n " +
                                "Please choose the meeting participants and the meeting date.",
                    ),
                modalBlockBuilder.plainTextInputBlock(
                    TextInputContents(
                        title = "Meeting name",
                        placeholderText = "Meeting name",
                    ),
                ),
                modalBlockBuilder.plainTextInputBlock(
                    TextInputContents(
                        title = "Reason",
                        placeholderText = "Reason",
                    ),
                ),
                callbackCheckboxes.layout,
                multiUserSelectionContents.layout,
                modalBlockBuilder.simpleText(
                    text = "Select meetup time",
                    isMarkDown = false,
                ),
                timeScheduleBlock.layout,
                approvalLayout.layout,
            )
        val states =
            callbackCheckboxes.interactiveObjects +
                multiUserSelectionContents.interactiveObjects +
                timeScheduleBlock.interactiveObjects +
                approvalLayout.interactiveObjects
        return toLayoutBlocks(
            *blocks.toTypedArray(),
            states = states,
        )
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
    ): LayoutBlocks {
        val approvalLayout =
            modalBlockBuilder.approvalBlock(
                approvalContents = approvalContents,
            )
        val radioButtonLayout =
            modalBlockBuilder.radioButtonBlock(
                *timeScheduleInfo.rejectReasons
                    .toTypedArray(),
                description = "Capturing reasons for meeting absence",
            )
        val blocks =
            listOf(
                modalBlockBuilder.headerBlock(
                    text = "Time Schedule Notice",
                ),
                modalBlockBuilder.dividerBlock(),
                modalBlockBuilder
                    .calendarThumbnailBlock(
                        title = timeScheduleInfo.title,
                        markdownBody = timeScheduleInfo.description,
                    ),
                radioButtonLayout.layout,
                modalBlockBuilder.plainTextInputBlock(
                    contents =
                        TextInputContents(
                            "detail reason",
                            "",
                        ),
                ),
                approvalLayout.layout,
            )
        val states = approvalLayout.interactiveObjects + radioButtonLayout.interactiveObjects
        return toLayoutBlocks(
            *blocks.toTypedArray(),
            states = states,
        )
    }
}
