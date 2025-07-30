package dev.notypie.domain.command.entity.slash
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.form.RequestTaskContext
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

class RequestTaskCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackEventBuilder: SlackEventBuilder,
    eventPublisher: EventPublisher
) : Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackEventBuilder = slackEventBuilder,
    eventPublisher = eventPublisher
) {
    override fun parseContext(subCommand: SubCommand): CommandContext = RequestTaskContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
        slackEventBuilder = this.slackEventBuilder,
        events = this.events,
        subCommand = subCommand
    )

    override fun findSubCommandDefinition(): SubCommandDefinition {
        TODO("Not yet implemented")
    }
}

internal const val TASK_COMMAND_IDENTIFIER: String = "task"
enum class TaskSubCommandDefinition(
    override val minRequiredArgs: Int = 0,
    override val requiresArguments: Boolean = false,
): SubCommandDefinition {
    // to be added.
}