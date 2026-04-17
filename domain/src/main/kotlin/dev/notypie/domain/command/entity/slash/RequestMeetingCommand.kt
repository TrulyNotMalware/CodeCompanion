package dev.notypie.domain.command.entity.slash

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.exceptions.CommandErrorCode
import dev.notypie.domain.command.exceptions.SubCommandParseException
import dev.notypie.domain.command.findSubCommandByIdentifier
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.domain.history.entity.Status
import dev.notypie.domain.meet.entity.Meeting
import java.util.UUID

class RequestMeetingCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
) : Command<MeetingSubCommandDefinition>(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
    ) {
    override fun parseContext(
        subCommand: SubCommand<MeetingSubCommandDefinition>,
    ): CommandContext<MeetingSubCommandDefinition> =
        RequestMeetingContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            subCommand = subCommand,
            intents = intents,
        )

    override fun findSubCommandDefinition(): MeetingSubCommandDefinition {
        val identifier =
            commandData.subCommands.firstOrNull()
                ?: return MeetingSubCommandDefinition.NONE
        return findSubCommandByIdentifier<MeetingSubCommandDefinition>(identifier)
            ?: throw SubCommandParseException(
                commandName = this::class.java.simpleName,
                subCommandName = identifier,
                errorCode = CommandErrorCode.SUBCOMMAND_NOT_FOUND,
                details =
                    exceptionDetails {
                        "subCommandIdentifier" value identifier because "subcommand $identifier not found"
                    },
            )
    }
}

internal const val MEETING_COMMAND_IDENTIFIER: String = "meetup"

enum class MeetingSubCommandDefinition(
    override val subCommandIdentifier: String,
    override val usage: String,
    override val requiresArguments: Boolean = false,
    override val minRequiredArgs: Int = 0,
) : SubCommandDefinition {
    NONE(
        subCommandIdentifier = "",
        usage = "",
    ),
    LIST(
        subCommandIdentifier = "list",
        usage = "/${MEETING_COMMAND_IDENTIFIER} list [today | week | month]",
    ),
}

data class RequestMeetingContextResult(
    override val ok: Boolean,
    override val status: Status,
    val meeting: Meeting,
    val commandBasicInfo: CommandBasicInfo,
) : CommandOutput(
        ok = ok,
        status = status,
        apiAppId = commandBasicInfo.appId,
        idempotencyKey = commandBasicInfo.idempotencyKey,
        publisherId = commandBasicInfo.publisherId,
        channel = commandBasicInfo.channel,
        token = commandBasicInfo.appToken,
        commandType = CommandType.PIPELINE,
        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
    )
