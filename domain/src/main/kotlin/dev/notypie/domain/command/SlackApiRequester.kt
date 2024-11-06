package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

interface SlackApiRequester {
    fun doNothing(): CommandOutput
    fun simpleTextRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, simpleString: String, commandType: CommandType): CommandOutput
    fun simpleEphemeralTextRequest(textMessage: String, commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): CommandOutput
    fun detailErrorTextRequest(commandDetailType: CommandDetailType, errorClassName: String, errorMessage: String, details: String?, commandType: CommandType, commandBasicInfo: CommandBasicInfo): CommandOutput
    fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): CommandOutput
    fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, approvalContents: ApprovalContents, commandType: CommandType): CommandOutput
    fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                  selectionFields: List<SelectionContents>, reasonInput: TextInputContents? = null, commandType: CommandType): CommandOutput
    fun requestMeetingFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): CommandOutput

    //Replace message
    fun replaceOriginalText(markdownText: String, responseUrl: String, commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): CommandOutput
}