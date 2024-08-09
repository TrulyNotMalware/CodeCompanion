package dev.notypie.domain.command

import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandType

interface SlackApiRequester {
    fun simpleTextRequest(idempotencyKey: String, headLineText: String, channel: String, simpleString: String, commandType: CommandType): SlackApiResponse
    fun errorTextRequest(idempotencyKey: String, errorClassName: String, channel: String, errorMessage: String, details: String?, commandType: CommandType): SlackApiResponse
    fun simpleTimeScheduleRequest(idempotencyKey: String, headLineText: String, channel: String,  timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): SlackApiResponse
    fun simpleApplyRejectRequest(idempotencyKey: String, headLineText: String, channel: String, approvalContents: ApprovalContents, commandType: CommandType): SlackApiResponse
    fun simpleApprovalFormRequest(idempotencyKey: String, headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents? = null, commandType: CommandType): SlackApiResponse
}