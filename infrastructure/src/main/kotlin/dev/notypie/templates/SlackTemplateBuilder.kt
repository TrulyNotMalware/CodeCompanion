package dev.notypie.templates

import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.dto.LayoutBlocks

interface SlackTemplateBuilder {
    fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks
    fun simpleScheduleNoticeTemplate( headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks
    fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents): LayoutBlocks
    fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks
    fun requestApprovalFormTemplate(headLineText: String, selectionFields: List<SelectionContents>,
                                    approvalTargetUser: MultiUserSelectContents? = null, reasonInput: TextInputContents? = null,
                                    approvalContents: ApprovalContents? = null): LayoutBlocks
    fun requestMeetingFormTemplate(approvalContents: ApprovalContents? = null,
                                   commandDetailType: CommandDetailType, idempotencyKey: String):LayoutBlocks
}