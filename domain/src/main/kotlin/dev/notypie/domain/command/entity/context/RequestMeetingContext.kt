package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

class RequestMeetingContext(
    commandBasicInfo: CommandBasicInfo,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
) : CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo
){
    override fun parseCommandType(): CommandType = CommandType.PIPELINE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.REQUEST_MEETING_FORM

    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.requestMeetingFormRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandType = this.commandType,
            commandDetailType = this.commandDetailType
            )


}