package dev.notypie.templates

import com.slack.api.model.block.LayoutBlock
import dev.notypie.domain.command.dto.modals.*

interface SlackTemplateBuilder {
    fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): List<LayoutBlock>
    fun simpleScheduleNoticeTemplate( headLineText: String, timeScheduleInfo: TimeScheduleInfo): List<LayoutBlock>
    fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents): List<LayoutBlock>
    fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): List<LayoutBlock>
    fun requestApprovalFormTemplate(headLineText: String, selectionFields: List<SelectionContents>,
                                    approvalTargetUser: MultiUserSelectContents? = null, reasonInput: TextInputContents? = null,
                                    approvalContents: ApprovalContents? = null): List<LayoutBlock>
}