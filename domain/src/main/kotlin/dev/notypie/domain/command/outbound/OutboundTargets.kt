package dev.notypie.domain.command.outbound

@JvmInline
value class ConversationTarget(
    val id: String,
)

enum class ConversationKind {
    CHANNEL,
    DIRECT,
    GROUP,
}

fun ConversationTarget.kind(): ConversationKind =
    when (id.firstOrNull()) {
        'D' -> ConversationKind.DIRECT
        'G' -> ConversationKind.GROUP
        else -> ConversationKind.CHANNEL
    }

@JvmInline
value class UserRef(
    val id: String,
)

data class MessageRef(
    val conversation: ConversationTarget,
    val messageId: String,
)
