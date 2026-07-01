package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.intent.CommandEffect

/**
 * Transport-neutral description of an outbound effect a CommandContext emits; a transport adapter
 * renders it into a staged CommandEvent. No Slack type appears here.
 */
sealed interface OutboundMessage : CommandEffect {
    data class ChannelMessage(
        val target: ConversationTarget,
        val content: MessageContent,
    ) : OutboundMessage

    data class Ephemeral(
        val target: ConversationTarget,
        val recipient: UserRef? = null,
        val content: MessageContent,
    ) : OutboundMessage

    data class DirectMessage(
        val recipient: UserRef,
        val content: MessageContent,
    ) : OutboundMessage

    data class UpdateMessage(
        val ref: MessageRef,
        val content: MessageContent,
        /**
         * Per-emitter routing type so a chat.update can be routed back to the correct context on
         * later interaction. Per-emitter rather than constant.
         */
        val detailType: CommandDetailType,
    ) : OutboundMessage

    data class ReplaceMessage(
        val handle: ResponseReplaceHandle,
        val content: MessageContent,
    ) : OutboundMessage

    data class OpenModal(
        val handle: ModalOpenHandle,
        val form: ModalForm,
    ) : OutboundMessage

    data class Approval(
        val target: ConversationTarget,
        val recipient: UserRef?,
        val approval: ApprovalContents,
        val routingExtras: List<String> = emptyList(),
    ) : OutboundMessage

    data class Notice(
        val target: ConversationTarget,
        val mentions: Collection<UserRef>,
        val message: String,
    ) : OutboundMessage
}
