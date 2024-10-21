package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.dto.InteractionLayoutBlock
import dev.notypie.templates.dto.LayoutBlocks

/**
 * A class that implements the SlackTemplateBuilder interface and provides methods for building modal templates.
 */
class ModalTemplateBuilder(
    private val modalBlockBuilder: ModalBlockBuilder = ModalBlockBuilder(),
): SlackTemplateBuilder {
    companion object {
        const val DEFAULT_PLACEHOLDER_TEXT = "SELECT"
    }

    private fun createLayouts(vararg blocks: LayoutBlock) = listOf(*blocks)

    private fun toLayoutBlocks(vararg blocks: LayoutBlock, states: List<States> = listOf()) =
        LayoutBlocks(interactionStates = states, template = this.createLayouts(blocks = blocks))

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

    override fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents): LayoutBlocks{
        val buttonLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents)
        return this.toLayoutBlocks(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.textBlock("type= ${approvalContents.type}",
                "reason= ${approvalContents.reason}", isMarkDown = true),
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
        headLineText: String, selectionFields: List<SelectionContents>,
        approvalTargetUser: MultiUserSelectContents?, reasonInput: TextInputContents?,
        approvalContents: ApprovalContents?): LayoutBlocks
    {
        val approvalLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents ?:
            ApprovalContents(reason = "Request Approval", approvalButtonName = "Send", rejectButtonName = "Cancel",
            approvalInteractionValue = "request_apply", rejectInteractionValue = "request_cancel"))
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
        approvalContents: ApprovalContents?,
        commandDetailType: CommandDetailType,
        idempotencyKey: String
    ): LayoutBlocks
    {
        val multiUserSelectionContents = this.modalBlockBuilder.multiUserSelectBlock(contents =
            MultiUserSelectContents(title = "Select meeting members", placeholderText = DEFAULT_PLACEHOLDER_TEXT)
        )
        val concatenateString = this.concatenateIdempotencyKey(idempotencyKey = idempotencyKey, commandDetailType = commandDetailType)
        val approvalLayout = this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents ?:
        ApprovalContents(reason = "Request Approval", approvalButtonName = "Send", rejectButtonName = "Cancel",
            approvalInteractionValue = concatenateString, rejectInteractionValue = concatenateString))

        val blocks = mutableListOf(
            this.modalBlockBuilder.headerBlock(text = "Request Meeting"),
            this.modalBlockBuilder.dividerBlock(),
            multiUserSelectionContents.layout,
            approvalLayout.layout
        )
        val states = mutableListOf<States>().apply {
            addAll(multiUserSelectionContents.interactiveObjects)
            approvalLayout.interactiveObjects
        }
        return this.toLayoutBlocks(*blocks.toTypedArray(), states = states)
    }

    private fun concatenateIdempotencyKey(idempotencyKey: String, commandDetailType: CommandDetailType) = "${idempotencyKey},${commandDetailType}"
}