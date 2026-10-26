package dev.notypie.impl.command.slack

import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.common.splitLineBreaks

private const val BLOCK_TYPE_RICH_TEXT = "rich_text"
private const val ELEMENT_TYPE_TEXT_SECTION = "rich_text_section"
private const val ELEMENT_TYPE_PREFORMATTED = "rich_text_preformatted"
private const val ELEMENT_TYPE_QUOTE = "rich_text_quote"
private const val ELEMENT_TYPE_LIST = "rich_text_list"
private const val ELEMENT_TYPE_USER = "user"
private const val ELEMENT_TYPE_TEXT = "text"
private const val ELEMENT_TYPE_LINK = "link"
private const val ELEMENT_TYPE_CHANNEL = "channel"
private const val ELEMENT_TYPE_USERGROUP = "usergroup"
private const val ELEMENT_TYPE_EMOJI = "emoji"
private const val ELEMENT_TYPE_BROADCAST = "broadcast"
private const val ELEMENT_TYPE_DATE = "date"
private const val LIST_STYLE_ORDERED = "ordered"
private const val CODE_FENCE = "```"
private val LINE_BREAK_CHARS: CharArray =
    charArrayOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')

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
                    ?.splitOnWhitespace()
                    ?.forEach { commandTokens.add(it) }
            }
        }
    }

    return InboundCommand(
        appId = appId,
        actorId = event.userId.orEmpty(),
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
                text = event.blocks.restoreMentionText(botId = botId),
            ),
    )
}

private fun String.splitOnWhitespace(): List<String> =
    buildList {
        val token = StringBuilder()
        for (char in this@splitOnWhitespace) {
            if (char.isWhitespace() || char in LINE_BREAK_CHARS) {
                if (token.isNotEmpty()) add(token.toString())
                token.setLength(0)
            } else {
                token.append(char)
            }
        }
        if (token.isNotEmpty()) add(token.toString())
    }

internal fun List<Block>.restoreMentionText(botId: String): String =
    filter { it.type == BLOCK_TYPE_RICH_TEXT }
        .flatMap { it.elements }
        .joinToString(separator = "\n") { it.restoreContainer(botId = botId).trimEnd('\n') }
        .trim()

private fun Element.restoreContainer(botId: String): String =
    when (type) {
        ELEMENT_TYPE_PREFORMATTED -> "$CODE_FENCE\n${restoreInline(botId = botId).trim('\n')}\n$CODE_FENCE"

        ELEMENT_TYPE_QUOTE ->
            restoreInline(botId = botId)
                .trimEnd(chars = LINE_BREAK_CHARS)
                .splitLineBreaks()
                .joinToString(separator = "\n") { "> $it" }

        ELEMENT_TYPE_LIST ->
            elements
                .mapIndexed { index, item ->
                    val marker = if (styleName() == LIST_STYLE_ORDERED) "${index + 1}." else "-"
                    "${"  ".repeat(n = indent)}$marker ${item.restoreInline(botId = botId).trimEnd('\n')}"
                }.joinToString(separator = "\n")

        else -> restoreInline(botId = botId)
    }

private fun Element.restoreInline(botId: String): String =
    elements.joinToString(separator = "") { it.restoreLeaf(botId = botId) }

private fun Element.restoreLeaf(botId: String): String =
    when (type) {
        ELEMENT_TYPE_TEXT -> extractText().orEmpty().let { if (isInlineCode()) "`$it`" else it }
        ELEMENT_TYPE_USER -> userId?.takeIf { it != botId }?.let { "<@$it>" }.orEmpty()
        ELEMENT_TYPE_LINK -> {
            val label = extractText()
            when {
                label.isNullOrBlank() || label == url -> url.orEmpty()
                url.isNullOrBlank() -> label
                else -> "$label ($url)"
            }
        }
        ELEMENT_TYPE_CHANNEL -> channelId?.let { "<#$it>" }.orEmpty()
        ELEMENT_TYPE_USERGROUP -> usergroupId?.let { "<!subteam^$it>" }.orEmpty()
        ELEMENT_TYPE_EMOJI -> name?.let { ":$it:" }.orEmpty()
        ELEMENT_TYPE_BROADCAST -> range?.let { "@$it" }.orEmpty()
        ELEMENT_TYPE_DATE -> fallback.orEmpty()
        else -> extractText().orEmpty()
    }

private fun Element.styleName(): String? = style?.takeIf { it.isString }?.asString()

private fun Element.isInlineCode(): Boolean = style?.path("code")?.asBoolean() == true
