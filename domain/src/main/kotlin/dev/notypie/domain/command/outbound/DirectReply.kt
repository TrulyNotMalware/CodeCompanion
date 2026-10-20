package dev.notypie.domain.command.outbound

import dev.notypie.domain.common.escapeMarkup
import dev.notypie.domain.common.splitLineBreaks
import dev.notypie.domain.common.takeEntitySafe

private const val MAX_QUOTED_PROMPT_LENGTH: Int = 1_500
private const val QUOTED_PROMPT_CUT_MARKER: String = "…"

data class DirectReply(
    val recipient: UserRef,
    val content: MessageContent.Text,
)

fun channelRefusedNotice(channel: ConversationTarget, recipient: UserRef): DirectReply =
    DirectReply(
        recipient = recipient,
        content =
            MessageContent.Text(
                headline = null,
                markdown =
                    when (channel.kind()) {
                        ConversationKind.CHANNEL ->
                            "I couldn't reply in <#${channel.id.escapeMarkup()}>: the post there was refused. " +
                                "If I'm not a member of that channel, invite me and try again."

                        ConversationKind.DIRECT ->
                            "I couldn't reply in that direct message: I can only post in conversations I'm part of. " +
                                "Run the command again in a channel I've joined."

                        ConversationKind.GROUP ->
                            "I couldn't reply in that conversation: I can only post in conversations I'm part of. " +
                                "Add me to it, or run the command again in a channel I've joined."
                    },
            ),
    )

fun askRefusedNotice(channel: ConversationTarget, recipient: UserRef, prompt: String): DirectReply =
    DirectReply(
        recipient = recipient,
        content =
            MessageContent.Text(
                headline = null,
                markdown = "${askRefusedReason(channel = channel)}\n\n${prompt.quotedForNotice()}",
            ),
    )

fun directFallbackOf(channel: ConversationTarget, recipient: UserRef, content: MessageContent.Text): DirectReply =
    DirectReply(
        recipient = recipient,
        content = content.copy(markdown = "${fallbackReason(channel = channel)}\n${content.markdown}"),
    )

private fun askRefusedReason(channel: ConversationTarget): String =
    when (channel.kind()) {
        ConversationKind.CHANNEL ->
            "I couldn't post your question in <#${channel.id.escapeMarkup()}>: the post there was refused, " +
                "so it was *not* answered. If I'm not a member of that channel, invite me and ask again, " +
                "or ask in a channel I've joined."

        ConversationKind.DIRECT ->
            "I couldn't post your question in that direct message, so it was *not* answered. " +
                "I can only post in conversations I'm part of: ask again in a channel I've joined."

        ConversationKind.GROUP ->
            "I couldn't post your question in that conversation, so it was *not* answered. " +
                "Add me to it and ask again, or ask in a channel I've joined."
    }

private fun fallbackReason(channel: ConversationTarget): String =
    when (channel.kind()) {
        ConversationKind.CHANNEL -> "_(Sent here because my reply in <#${channel.id.escapeMarkup()}> was refused.)_"
        ConversationKind.DIRECT -> "_(Sent here because I can't post in that direct message.)_"
        ConversationKind.GROUP -> "_(Sent here because I can't post in that conversation.)_"
    }

private fun String.quotedForNotice(): String {
    val escaped = escapeMarkup()
    val quoted =
        if (escaped.length <= MAX_QUOTED_PROMPT_LENGTH) {
            escaped
        } else {
            escaped.takeEntitySafe(maxLength = MAX_QUOTED_PROMPT_LENGTH) + QUOTED_PROMPT_CUT_MARKER
        }
    return quoted.splitLineBreaks().joinToString(separator = "\n") { line -> "> $line" }
}
