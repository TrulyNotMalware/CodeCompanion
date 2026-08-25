package dev.notypie.domain.command.outbound

@JvmInline
value class ConversationTarget(
    val id: String,
)

@JvmInline
value class UserRef(
    val id: String,
)

data class MessageRef(
    val conversation: ConversationTarget,
    val messageId: String,
)
