package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

internal class StandupFillContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_PROMPT

    /**
     * "Fill in standup" button carries `<idempotencyKey>,STANDUP_PROMPT,<sessionUid>,<routineUid>`;
     * routingExtras[0] = sessionUid, [1] = routineUid. Missing/malformed extras are a no-op.
     */
    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val sessionUid =
            interaction.routingExtras
                .getOrNull(0)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        val routineUid =
            interaction.routingExtras
                .getOrNull(1)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return CommandOutput.success(
                    basicInfo = commandBasicInfo,
                    commandType = commandType,
                    commandDetailType = commandDetailType,
                )
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = interaction.trigger.raw),
                form =
                    ModalForm.StandupFill(
                        sessionUid = sessionUid,
                        routineUid = routineUid,
                        requesterId = interaction.actor.id,
                        originNotice =
                            MessageRef(
                                conversation = ConversationTarget(id = interaction.channelId),
                                messageId = interaction.message?.raw.orEmpty(),
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
