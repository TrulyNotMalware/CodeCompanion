package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.RequestMeetingContext

class RequestMeetingCommand(
    idempotencyKey: String,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester
) {
    private val context = RequestMeetingContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
        slackApiRequester = this.slackApiRequester,
    )
    override fun handleEvent(): SlackApiResponse = this.context.runCommand()

}