package dev.notypie.domain.command

import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

interface SlackApiRequester {
    fun simpleTextRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String, simpleString: String, commandType: CommandType): SlackApiResponse
    fun errorTextRequest(commandDetailType: CommandDetailType, idempotencyKey: String, errorClassName: String, channel: String, errorMessage: String, details: String?, commandType: CommandType): SlackApiResponse
    fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String, timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): SlackApiResponse
    fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String, approvalContents: ApprovalContents, commandType: CommandType): SlackApiResponse
    fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, idempotencyKey: String, headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents? = null, commandType: CommandType): SlackApiResponse
}