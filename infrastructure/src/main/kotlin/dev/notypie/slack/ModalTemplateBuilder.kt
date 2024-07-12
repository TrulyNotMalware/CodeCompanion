package dev.notypie.slack

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo

/**
 * A class that implements the SlackTemplateBuilder interface and provides methods for building modal templates.
 */
class ModalTemplateBuilder(
    private val modalBlockBuilder: ModalBlockBuilder = ModalBlockBuilder(),
): SlackTemplateBuilder {

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
        createLayouts(
            modalBlockBuilder.headerBlock(text = headLineText),
            modalBlockBuilder.dividerBlock(),
            modalBlockBuilder.textBlock("type = exception", "reason = $errorMessage")
        ) + (
                if(!details.isNullOrBlank())
                    listOf(modalBlockBuilder.simpleText(text = details, isMarkDown = false))
                else
                    listOf()
                )
}