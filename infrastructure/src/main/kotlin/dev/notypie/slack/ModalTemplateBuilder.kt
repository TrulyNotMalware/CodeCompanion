package dev.notypie.slack

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.modals.*

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

    override fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): List<LayoutBlock>
    = this.createLayouts(
        this.modalBlockBuilder.headerBlock(text = headLineText),
        this.modalBlockBuilder.dividerBlock(),
        this.modalBlockBuilder.simpleText(text = body, isMarkDown = isMarkDown)
    )

    override fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): List<LayoutBlock> =
        this.createLayouts(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.timeScheduleBlock(timeScheduleInfo = timeScheduleInfo)
        )

    override fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents): List<LayoutBlock> =
        this.createLayouts(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.textBlock("type= ${approvalContents.type}",
                "reason= ${approvalContents.reason}", isMarkDown = true),
            this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents)
        )

    override fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): List<LayoutBlock> =
        this.createLayouts(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            this.modalBlockBuilder.textBlock("type = exception", "reason = $errorMessage")
        ) + (
                if(!details.isNullOrBlank())
                    listOf(this.modalBlockBuilder.simpleText(text = details, isMarkDown = false))
                else listOf()
                )

    override fun requestApprovalFormTemplate(headLineText: String, selectionFields: List<SelectionContents>,
                                    approvalTargetUser: MultiUserSelectContents?, reasonInput: TextInputContents?,
                                             approvalContents: ApprovalContents?): List<LayoutBlock> =
        this.createLayouts(
            this.modalBlockBuilder.headerBlock(text = headLineText),
            this.modalBlockBuilder.dividerBlock(),
            *selectionFields.map { this.modalBlockBuilder.selectionBlock(selectionContents = it) }.toTypedArray(),
            this.modalBlockBuilder.multiUserSelectBlock(
                contents = approvalTargetUser ?:
                MultiUserSelectContents(title = "Select target user", placeholderText = DEFAULT_PLACEHOLDER_TEXT)
            )
        ) + (
                if(reasonInput != null){
                    listOf(
                        this.modalBlockBuilder.plainTextInputBlock(contents = reasonInput),
                        this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents ?:
                        ApprovalContents(type = "", reason = "Request Approval", approvalButtonName = "Send", rejectButtonName = "Cancel",
                            approvalInteractionValue = "request_apply", rejectInteractionValue = "request_cancel"))
                    )
                } else listOf(
                    this.modalBlockBuilder.approvalBlock(approvalContents = approvalContents ?:
                    ApprovalContents(type = "", reason = "Request Approval", approvalButtonName = "Send", rejectButtonName = "Cancel",
                        approvalInteractionValue = "request_apply", rejectInteractionValue = "request_cancel"))
                )
            )
}