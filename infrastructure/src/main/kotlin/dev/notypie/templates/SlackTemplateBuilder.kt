package dev.notypie.templates

import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import java.util.UUID

interface SlackTemplateBuilder {
    fun onlyTextTemplate(message: String, isMarkDown: Boolean) : LayoutBlocks
    fun simpleTextResponseTemplate( headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks
    fun simpleScheduleNoticeTemplate( headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks
    fun approvalTemplate(headLineText: String, approvalContents: ApprovalContents, idempotencyKey: UUID, commandDetailType: CommandDetailType): LayoutBlocks
    fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks
    fun requestApprovalFormTemplate(headLineText: String, selectionFields: List<SelectionContents>, approvalContents: ApprovalContents,
                                    approvalTargetUser: MultiUserSelectContents? = null, reasonInput: TextInputContents? = null): LayoutBlocks
    fun requestMeetingFormTemplate(approvalContents: ApprovalContents):LayoutBlocks
    fun timeScheduleNoticeTemplate(
        timeScheduleInfo: TimeScheduleAlertContents,
        approvalContents: ApprovalContents): LayoutBlocks
    // task form template added.
    fun requestTaskFormTemplate(): LayoutBlocks
}