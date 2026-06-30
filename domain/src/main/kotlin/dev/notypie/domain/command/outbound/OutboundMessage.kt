package dev.notypie.domain.command.outbound

import dev.notypie.domain.command.dto.modals.ApprovalContents

/**
 * Transport-neutral description of an outbound effect that a CommandContext emits; a transport
 * adapter (Phase 3c SlackOutboundStager) renders it into a staged CommandEvent. No Slack type
 * appears here.
 */
sealed interface OutboundMessage {
    data class ChannelMessage(
        val target: ConversationTarget,
        val content: MessageContent,
    ) : OutboundMessage

    data class Ephemeral(
        val target: ConversationTarget,
        val recipient: UserRef,
        val content: MessageContent,
    ) : OutboundMessage

    data class DirectMessage(
        val recipient: UserRef,
        val content: MessageContent,
    ) : OutboundMessage

    data class UpdateMessage(
        val ref: MessageRef,
        val content: MessageContent,
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
