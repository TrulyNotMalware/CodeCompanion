package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.intent.CommandEffect

sealed interface OutboundMessage : CommandEffect {
    data class ChannelMessage(
        val target: ConversationTarget,
        val content: MessageContent,
        val detailType: CommandDetailType? = null,
        val threadId: String? = null,
    ) : OutboundMessage

    data class Ephemeral(
        val target: ConversationTarget,
        val recipient: UserRef? = null,
        val content: MessageContent,
        val detailType: CommandDetailType? = null,
    ) : OutboundMessage

    data class DirectMessage(
        val recipient: UserRef,
        val content: MessageContent,
    ) : OutboundMessage

    data class UpdateMessage(
        val ref: MessageRef,
        val content: MessageContent.Text,
        val detailType: CommandDetailType,
    ) : OutboundMessage

    data class ReplaceMessage(
        val handle: ResponseReplaceHandle,
        val content: MessageContent.Text,
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
