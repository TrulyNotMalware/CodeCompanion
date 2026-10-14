package dev.notypie.domain.command.outbound

import dev.notypie.domain.common.escapeMarkup

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
                    "I couldn't reply in <#${channel.id.escapeMarkup()}>: the post there was refused. " +
                        "If I'm not a member of that channel, invite me and try again.",
            ),
    )

fun directFallbackOf(channel: ConversationTarget, recipient: UserRef, content: MessageContent.Text): DirectReply =
    DirectReply(
        recipient = recipient,
        content =
            content.copy(
                markdown =
                    "_(Sent here because my reply in <#${channel.id.escapeMarkup()}> was refused.)_\n" +
                        content.markdown,
            ),
    )
