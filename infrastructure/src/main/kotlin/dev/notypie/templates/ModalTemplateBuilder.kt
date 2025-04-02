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

/**
 * A class that implements the SlackTemplateBuilder interface and provides methods for building modal templates.
 */
class ModalTemplateBuilder(
    private val modalBlockBuilder: ModalBlockBuilder = ModalBlockBuilder(),
    private val restRequester: RestRequester = RestClientRequester(
        baseUrl = SLACK_API_BASE_URL
    ),
    private val slackApiToken: String
): SlackTemplateBuilder {
    companion object {
        const val DEFAULT_PLACEHOLDER_TEXT = "SELECT"
    }

    private fun createLayouts(vararg blocks: LayoutBlock) = listOf(*blocks)

    private fun toLayoutBlocks(vararg blocks: LayoutBlock, states: List<States> = listOf()) =
        LayoutBlocks(interactionStates = states, template = this.createLayouts(blocks = blocks))

    override fun onlyTextTemplate(message: String, isMarkDown: Boolean) : LayoutBlocks =
        this.toLayoutBlocks(
            this.modalBlockBuilder.simpleText(text = message, isMarkDown = isMarkDown)
        )

    override fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks
    = this.toLayoutBlocks(
        this.modalBlockBuilder.headerBlock(text = headLineText),
        this.modalBlockBuilder.dividerBlock(),
        this.modalBlockBuilder.simpleText(text = body, isMarkDown = isMarkDown)
    )

    override fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks =
        this.toLayoutBlocks(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.timeScheduleBlock(timeScheduleInfo = timeScheduleInfo)
        )

    //Username with thumbnail Requires Role users.profile.get. Reference from https://api.slack.com/methods/users.profile.get
    override fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents, idempotencyKey: String, commandDetailType: CommandDetailType): LayoutBlocks{
        val buttonLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents)
        val user = this.restRequester.get(uri = "users.profile.get?user=${approvalContents.publisherId}",
            authorizationHeader = this.slackApiToken, responseType = SlackUserProfileDto::class.java)
            .body ?: throw IllegalArgumentException("User profile is not found.")
        return this.toLayoutBlocks(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.userNameWithThumbnailBlock(
                userName = user.profile.displayName,
                userThumbnailUrl = user.profile.imageSize24,
                mkdIntroduceComment = "*Publisher* :"
            ),
            this.modalBlockBuilder.textBlock("*${approvalContents.subTitle}*", isMarkDown = true),
            buttonLayout.layout, states = buttonLayout.interactiveObjects
        )
    }

    override fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks{
        val blocks = mutableListOf(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.textBlock("type = exception", "reason = $errorMessage")
        )
        details?.let {
            blocks.add(this.modalBlockBuilder.simpleText(text = it, isMarkDown = false))
        }
        return this.toLayoutBlocks(*blocks.toTypedArray())
    }

    override fun requestApprovalFormTemplate(
        headLineText: String, selectionFields: List<SelectionContents>, approvalContents: ApprovalContents,
        approvalTargetUser: MultiUserSelectContents?, reasonInput: TextInputContents? ): LayoutBlocks
    {
        val approvalLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents)
        val userSelectLayout = this.modalBlockBuilder.multiUserSelectBlock(
            contents = approvalTargetUser ?:
            MultiUserSelectContents(title = "Select target user", placeholderText = DEFAULT_PLACEHOLDER_TEXT))

        val selectionLayouts: List<InteractionLayoutBlock> = selectionFields.map { this.modalBlockBuilder.selectionBlock(selectionContents = it) }

        val blocks = mutableListOf(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
        ).apply {
            addAll(selectionLayouts.map { it.layout })
            add(userSelectLayout.layout)
            reasonInput?.let { add(modalBlockBuilder.plainTextInputBlock(contents = reasonInput)) }
            add(approvalLayout.layout)
        }

        val states = mutableListOf<States>().apply {
            addAll(userSelectLayout.interactiveObjects)
            addAll(selectionLayouts.flatMap { it.interactiveObjects })
            addAll(approvalLayout.interactiveObjects)
        }

        return this.toLayoutBlocks(*blocks.toTypedArray(), states = states)
    }

    override fun requestMeetingFormTemplate(
        approvalContents: ApprovalContents
    ): LayoutBlocks
    {
        val callbackCheckboxes = this.modalBlockBuilder.checkBoxesBlock(
            CheckBoxOptions(text = "*Confirmation CallBack*", description = "Send confirmation request to all participants and receive result")
        )
        val multiUserSelectionContents = this.modalBlockBuilder.multiUserSelectBlock(contents =
            MultiUserSelectContents(title = "Select meeting members", placeholderText = DEFAULT_PLACEHOLDER_TEXT)
        )
        val timeScheduleBlock = this.modalBlockBuilder.selectDateTimeScheduleBlock()
        val approvalLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents)

        val blocks = listOf(
            this.modalBlockBuilder.headerBlock(text = "Create new meeting"),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.calendarThumbnailBlock(
                title = "Schedule a new meeting",
                markdownBody = "Create a new meeting.\n Please choose the meeting participants and the meeting date."
            ),
            this.modalBlockBuilder.plainTextInputBlock(
                TextInputContents(title = "Meeting name", placeholderText = "Meeting name")
            ),
            this.modalBlockBuilder.plainTextInputBlock(
                TextInputContents(title = "Reason", placeholderText = "Reason")
            ),
            callbackCheckboxes.layout,
            multiUserSelectionContents.layout,
            this.modalBlockBuilder.simpleText(text = "Select meetup time", isMarkDown = false),
            timeScheduleBlock.layout,
            approvalLayout.layout
        )
        val states = mutableListOf<States>().apply {
            addAll(callbackCheckboxes.interactiveObjects)
            addAll(multiUserSelectionContents.interactiveObjects)
            addAll(timeScheduleBlock.interactiveObjects)
            addAll(approvalLayout.interactiveObjects)
        }
        return this.toLayoutBlocks(*blocks.toTypedArray(), states = states)
    }

    override fun timeScheduleNoticeTemplate(
        timeScheduleInfo: TimeScheduleAlertContents,
        approvalContents: ApprovalContents
    ): LayoutBlocks {
        val approvalLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents)
        val radioButtonLayout = this.modalBlockBuilder.radioButtonBlock(
            *timeScheduleInfo.rejectReasons.toTypedArray(),
            description = "Capturing reasons for meeting absence"
        )
        val blocks = listOf(
            this.modalBlockBuilder.headerBlock(text = "Time Schedule Notice"),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.calendarThumbnailBlock(
                title = timeScheduleInfo.title,
                markdownBody = timeScheduleInfo.description
            ),
            radioButtonLayout.layout,
            this.modalBlockBuilder.plainTextInputBlock(
                contents = TextInputContents("detail reason", "")
            ),
            approvalLayout.layout,
        )
        val states = mutableListOf<States>().apply {
            addAll(approvalLayout.interactiveObjects)
            addAll(radioButtonLayout.interactiveObjects)
        }
        return this.toLayoutBlocks(*blocks.toTypedArray(), states = states)
    }
}