package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.SendSlackMessageEvent

interface SlackEventBuilder {
    fun simpleTextRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, simpleString: String, commandType: CommandType): SendSlackMessageEvent
    fun simpleEphemeralTextRequest(textMessage: String, commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType, targetUserId: String? = null): SendSlackMessageEvent
    fun detailErrorTextRequest(commandDetailType: CommandDetailType, errorClassName: String, errorMessage: String, details: String?, commandType: CommandType, commandBasicInfo: CommandBasicInfo): SendSlackMessageEvent
    fun simpleTimeScheduleRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo, timeScheduleInfo: TimeScheduleInfo, commandType: CommandType): SendSlackMessageEvent
    fun simpleApplyRejectRequest(commandDetailType: CommandDetailType, commandBasicInfo: CommandBasicInfo, approvalContents: ApprovalContents, commandType: CommandType, targetUserId: String? = null): SendSlackMessageEvent
    fun simpleApprovalFormRequest(commandDetailType: CommandDetailType, headLineText: String, commandBasicInfo: CommandBasicInfo,
                                  selectionFields: List<SelectionContents>, commandType: CommandType, reasonInput: TextInputContents? = null, approvalContents: ApprovalContents? = null): SendSlackMessageEvent
    fun requestMeetingFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType, approvalContents: ApprovalContents? = null): SendSlackMessageEvent

    //Replace message
    fun replaceOriginalText(markdownText: String, responseUrl: String, commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): SendSlackMessageEvent
    // task form request added.
    fun requestTaskFormRequest(commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType): SendSlackMessageEvent
}