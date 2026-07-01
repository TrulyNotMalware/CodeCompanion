package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

internal class StandupFillContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_FILL

    /**
     * "Fill in standup" button carries `<idempotencyKey>,STANDUP_FILL,<sessionUid>,<routineUid>`;
     * routingExtras[0] = sessionUid, [1] = routineUid. Missing/malformed extras are a no-op.
     */
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val sessionUid =
            interactionPayload.routingExtras
                .getOrNull(0)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        val routineUid =
            interactionPayload.routingExtras
                .getOrNull(1)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = interactionPayload.triggerId),
                form =
                    ModalForm.StandupFill(
                        sessionUid = sessionUid,
                        routineUid = routineUid,
                        requesterId = interactionPayload.user.id,
                        originNotice =
                            MessageRef(
                                conversation = ConversationTarget(id = interactionPayload.channel.id),
                                messageId = interactionPayload.container.messageTs.orEmpty(),
                            ),
                    ),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
