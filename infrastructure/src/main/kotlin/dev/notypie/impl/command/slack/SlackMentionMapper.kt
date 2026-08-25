package dev.notypie.impl.command.slack

import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.inbound.MessageHandle

private const val BLOCK_TYPE_RICH_TEXT = "rich_text"
private const val ELEMENT_TYPE_TEXT_SECTION = "rich_text_section"
private const val ELEMENT_TYPE_USER = "user"
private const val ELEMENT_TYPE_TEXT = "text"
private const val COMMAND_DELIMITER = " "

/**
 * Flattens a Slack app_mention event into a transport-neutral [InboundCommand] carrying a
 * [MentionInvocation]. The rich_text walk is hosted here (moved verbatim from the old domain
 * `AppMentionContextParser`): the bot id comes from `authorizations`, user mentions are filtered
 * against it, and command text is split on spaces with blanks dropped. `hasCommandStructure` is
 * true iff a `rich_text_section` was found, so the domain can distinguish "not supported" (no
 * structure) from "empty command" (structure but no tokens).
 */
fun SlackEventCallBackRequest.toMentionInboundCommand(
    appId: String,
    channelName: String,
    actorName: String,
): InboundCommand {
    val botId = authorizations.find { it.isBot }?.userId ?: ""
    val section =
        event.blocks
            .find { block -> block.elements.isNotEmpty() && block.type == BLOCK_TYPE_RICH_TEXT }
            ?.elements
            ?.find { element -> element.type == ELEMENT_TYPE_TEXT_SECTION }

    val mentionedUserIds = mutableListOf<String>()
    val commandTokens = mutableListOf<String>()
    section?.elements?.forEach { element ->
        when (element.type) {
            ELEMENT_TYPE_USER -> {
                val userId = element.userId
                if (userId != null && userId != botId) mentionedUserIds.add(userId)
            }

            ELEMENT_TYPE_TEXT -> {
                element
                    .extractText()
                    ?.split(COMMAND_DELIMITER)
                    ?.filter { it.isNotBlank() }
                    ?.forEach { commandTokens.add(it) }
            }
        }
    }

    return InboundCommand(
        appId = appId,
        appToken = token,
        actorId = event.userId,
        actorName = actorName,
        channel = event.channel,
        channelName = channelName,
        teamId = teamId,
        kind = InboundKind.MENTION,
        payload =
            MentionInvocation(
                mentionedUserIds = mentionedUserIds,
                commandTokens = commandTokens,
                hasCommandStructure = section != null,
                message = event.ts.takeIf { it.isNotBlank() }?.let { MessageHandle(raw = it) },
                thread = event.threadTs?.takeIf { it.isNotBlank() }?.let { MessageHandle(raw = it) },
            ),
    )
}
