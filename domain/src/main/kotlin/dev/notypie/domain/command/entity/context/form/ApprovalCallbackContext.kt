package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext

internal class ApprovalCallbackContext(
    commandBasicInfo: CommandBasicInfo,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    private val participants: Set<String>
) : CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo
){
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.NOTICE_FORM

    // 다수에게 apply request form 을 뿌림.
    override fun runCommand(): CommandOutput {
        return super.runCommand()
    }

    // interaction 은 replace 해주어야 함. 승인, 거절 여부
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {

        return this.interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
    }
}