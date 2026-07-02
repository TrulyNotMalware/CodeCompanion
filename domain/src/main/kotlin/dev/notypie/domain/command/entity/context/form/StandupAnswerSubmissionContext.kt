package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

internal class StandupAnswerSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val s =
            interaction.submission as? InboundSubmission.StandupAnswer
                ?: return successOutput()
        val sessionUid =
            runCatching { UUID.fromString(s.sessionUidRaw) }
                .getOrElse {
                    return successOutput()
                }
        val userId = s.userId.ifBlank { interaction.actor.id }
        val noticeChannel = s.noticeChannel
        val noticeMessageTs = s.noticeMessageTs
        // Answers arrive already trimmed and ordered by question index from the inbound mapper.
        val responses = s.answers

        if (responses.isNotEmpty()) {
            addIntent(
                CommandIntent.RecordStandupAnswer(
                    sessionUid = sessionUid,
                    userId = userId,
                    responses = responses,
                ),
            )
        }
        if (noticeChannel.isNotBlank() && noticeMessageTs.isNotBlank()) {
            addOutbound(
                OutboundMessage.UpdateMessage(
                    ref =
                        MessageRef(
                            conversation = ConversationTarget(id = noticeChannel),
                            messageId = noticeMessageTs,
                        ),
                    content = MessageContent.Text(headline = null, markdown = "Standup submitted."),
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                ),
            )
        }
        return successOutput()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
}
