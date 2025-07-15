package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.findSubCommandByIdentifier
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

class RequestMeetingCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackEventBuilder: SlackEventBuilder,
    eventPublisher: EventPublisher
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackEventBuilder = slackEventBuilder,
    eventPublisher = eventPublisher
) {
    override fun parseContext(subCommand: SubCommand): CommandContext = RequestMeetingContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
        slackEventBuilder = this.slackEventBuilder, events = this.events, subCommand = subCommand
    )

    override fun findSubCommandDefinition(): SubCommandDefinition {
        val identifier = this.commandData.subCommands.firstOrNull()
            ?: return NoSubCommands()
        return findSubCommandByIdentifier<MeetingSubCommandDefinition>(identifier)
            ?: NoSubCommands()
    }

}

internal const val MEETING_COMMAND_IDENTIFIER: String = "meetup"
enum class MeetingSubCommandDefinition(
    override val subCommandIdentifier: String,
    override val usage: String,
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
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