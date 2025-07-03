package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

//FIXME return events
interface SlackApiRequester {
    fun simpleTextRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, simpleString: String, commandType: CommandType): CommandOutput
    fun simpleEphemeralTextRequest(textMessage: String, commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType, targetUserId: String? = null): CommandOutput
    fun detailErrorTextRequest(commandDetailType: CommandDetailType, errorClassName: String, errorMessage: String, details: String?, commandType: CommandType, commandBasicInfo: CommandBasicInfo): CommandOutput
    fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): CommandOutput
    fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, commandBasicInfo: CommandBasicInfo, approvalContents: ApprovalContents, commandType: CommandType, targetUserId: String? = null): CommandOutput
    fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                  selectionFields: List<SelectionContents>, commandType: CommandType, reasonInput: TextInputContents? = null, approvalContents: ApprovalContents? = null): CommandOutput
    fun requestMeetingFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType, approvalContents: ApprovalContents? = null): CommandOutput

    //Replace message
    fun replaceOriginalText(markdownText: String, responseUrl: String, commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): CommandOutput
}