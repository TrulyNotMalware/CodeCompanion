package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestClientRequester.Companion.SLACK_API_BASE_URL
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
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

        /**
         * Slack Block Kit caps each message at 50 blocks. Each meeting renders as:
         *   header(1) + top-divider(1) + N sections + (N-1) inter-dividers = 2N + 1
         * Plus, when overflow occurs, we add a single extra truncation-notice section
         * (no preceding divider — the italic notice is already visually distinct),
         * bringing the total to 2N + 2.
         * For 50-block safety we must keep 2N + 2 <= 50, i.e. N <= 24.
         * We choose 24 and, in the overflow branch, emit the notice without a leading
         * divider so the worst-case total is exactly 50 blocks.
         */
        internal const val MAX_MEETINGS_PER_LIST: Int = 24
    }

    private fun createLayouts(vararg blocks: LayoutBlock) =
        listOf(
            *blocks,
        )

    private fun collectStates(vararg stateLists: List<States>): List<States> = stateLists.flatMap { it }

    private fun toLayoutBlocks(vararg blocks: LayoutBlock, states: List<States> = listOf()) =
        LayoutBlocks(
            interactionStates = states,
            template = createLayouts(blocks = blocks),
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
            collectStates(
                userSelectLayout.interactiveObjects,
                selectionLayouts.flatMap { it.interactiveObjects },
                approvalLayout.interactiveObjects,
            )

        return toLayoutBlocks(
            *blocks.toTypedArray(),
            states = states,
        )
    }

    override fun meetingListFormTemplate(meetings: List<MeetingDto>): LayoutBlocks {
        val blocks =
            mutableListOf(
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
        val displayed = meetings.take(n = MAX_MEETINGS_PER_LIST)
        displayed.forEachIndexed { index, meeting ->
            blocks.add(
                modalBlockBuilder.simpleText(
                    text = renderMeetingSection(meeting = meeting),
                    isMarkDown = true,
                ),
            )
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
            // the divider keeps worst-case total at 2*MAX+2 = 50 blocks (Slack's cap).
            blocks.add(modalBlockBuilder.simpleText(text = notice, isMarkDown = true))
        }
        return toLayoutBlocks(*blocks.toTypedArray())
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
        val participantsLine = "Participants: ${meeting.participantIds.size}"
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
            collectStates(
                callbackCheckboxes.interactiveObjects,
                multiUserSelectionContents.interactiveObjects,
                timeScheduleBlock.interactiveObjects,
                approvalLayout.interactiveObjects,
            )
        return toLayoutBlocks(
            *blocks.toTypedArray(),
            states = states,
        )
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
        val states =
            collectStates(
                approvalLayout.interactiveObjects,
                radioButtonLayout.interactiveObjects,
            )
        return toLayoutBlocks(
            *blocks.toTypedArray(),
            states = states,
        )
    }
}
