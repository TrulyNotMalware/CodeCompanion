package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.SubCommandParser
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
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

internal const val MEETING_COMMAND_IDENTIFIER: String = "meetup"
enum class MeetingSubCommand(
    override val subCommandIdentifier: String,
    override val usage: String,
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0
): SubCommandDefinition {

    LIST(subCommandIdentifier = "list",
        usage = "/${MEETING_COMMAND_IDENTIFIER} list [today | week | month]")
}

//internal class MeetingSubCommandParser(
//
//): SubCommandParser<MeetingSubCommand>{
//
//    override fun parse(subCommands: List<String>): Pair<MeetingSubCommand, List<String>> {
//
//    }
//}