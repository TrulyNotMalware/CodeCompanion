package dev.notypie.templates

import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents

interface SlackTemplateBuilder {
    fun onlyTextTemplate(message: String, isMarkDown: Boolean) : LayoutBlocks
    fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks
    fun simpleScheduleNoticeTemplate( headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks
    //FIXME ApprovalContents idempotencyKey
    fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents, idempotencyKey: String, commandDetailType: CommandDetailType): LayoutBlocks
    fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks
    fun requestApprovalFormTemplate(headLineText: String, selectionFields: List<SelectionContents>,
                                    approvalTargetUser: MultiUserSelectContents? = null, reasonInput: TextInputContents? = null,
                                    approvalContents: ApprovalContents? = null): LayoutBlocks
    //FIXME ApprovalContents idempotencyKey
    fun requestMeetingFormTemplate(approvalContents: ApprovalContents? = null,
                                   commandDetailType: CommandDetailType, idempotencyKey: String):LayoutBlocks
    fun timeScheduleNoticeTemplate(
        timeScheduleInfo: TimeScheduleAlertContents,
        approvalContents: ApprovalContents?, idempotencyKey: String, commandDetailType: CommandDetailType, ): LayoutBlocks

}