package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

internal class StandupAnswerSubmissionContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val sessionUid =
            runCatching { UUID.fromString(interactionPayload.idempotencyKey) }
                .getOrElse {
                    return successOutput()
                }
        val userId =
            interactionPayload.routingExtras
                .getOrNull(0)
                ?.takeIf { it.isNotBlank() }
                ?: interactionPayload.user.id
        val noticeChannel = interactionPayload.routingExtras.getOrNull(1).orEmpty()
        val noticeMessageTs = interactionPayload.routingExtras.getOrNull(2).orEmpty()
        // Slack returns `view.state.values` unordered; sort by the `standup_q_<index>` block id so
        // `responses[i]` stays aligned with `routine.questions[i]`.
        val responses =
            interactionPayload.states
                .filter { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
                .sortedBy { state -> standupQuestionIndex(blockId = state.blockId) }
                .map { it.selectedValue.trim() }

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

    /** Trailing index of a `standup_q_<index>` block id; unparseable ids sort last via [Int.MAX_VALUE]. */
    private fun standupQuestionIndex(blockId: String?): Int {
        val tail = blockId?.removePrefix(STANDUP_QUESTION_BLOCK_ID_PREFIX)
        return tail?.toIntOrNull() ?: Int.MAX_VALUE
    }

    companion object {
        private const val STANDUP_QUESTION_BLOCK_ID_PREFIX = "standup_q_"
    }
}
