package dev.notypie.domain.command

import dev.notypie.domain.command.dto.SlackEventContents
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse

interface SlackApiRequester {
    //Simple String response
    @Deprecated(message = "")
    fun buildSimpleTextRequestBody(headLineText: String, channel: String, simpleString: String): SlackEventContents

    fun simpleTextRequest(headLineText: String, channel: String, simpleString: String): SlackApiResponse
    fun errorTextRequest(errorClassName: String, channel: String, errorMessage: String, details: String?): SlackApiResponse
    fun simpleTimeScheduleRequest(headLineText: String, channel: String,  timeScheduleInfo: TimeScheduleInfo): SlackApiResponse
    fun simpleApplyRejectRequest(headLineText: String, channel: String, approvalContents: ApprovalContents): SlackApiResponse
    fun simpleApprovalFormRequest(headLineText: String, channel: String,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents? = null): SlackApiResponse
}