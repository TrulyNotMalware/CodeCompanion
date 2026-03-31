package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.RestClientRequester.Companion.SLACK_API_BASE_URL
import dev.notypie.impl.command.RestRequester
import dev.notypie.impl.command.dto.SlackUserProfileDto
import dev.notypie.templates.dto.CheckBoxOptions
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
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

    override fun meetingListFormTemplate(): LayoutBlocks {
        TODO()
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
