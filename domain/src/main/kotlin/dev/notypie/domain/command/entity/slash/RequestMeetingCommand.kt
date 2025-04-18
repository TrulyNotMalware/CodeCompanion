package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import java.util.UUID

class RequestMeetingCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester
) {
    override fun parseContext(): CommandContext = RequestMeetingContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
        slackApiRequester = this.slackApiRequester,
    )
}