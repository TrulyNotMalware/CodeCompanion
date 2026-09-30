package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.common.IdempotencyData
import java.util.UUID

enum class InboundKind {
    SLASH,
    MENTION,
    INTERACTION,
}

sealed interface InboundPayload

data class SlashInvocation(
    val trigger: TriggerHandle,
) : InboundPayload

/**
 * [commandTokens] are the words of the first text section only, which is what command parsing needs. [text] is the
 * whole message restored as plain text in reading order — other people's mentions as `<@id>`, links with their url,
 * code blocks fenced, the bot's own mention removed — for free-text consumers such as the agent prompt. Blank when
 * the transport supplied none.
 */
data class MentionInvocation(
    val mentionedUserIds: List<String>,
    val commandTokens: List<String>,
    val hasCommandStructure: Boolean,
    val message: MessageHandle? = null,
    val thread: MessageHandle? = null,
    val text: String = "",
) : InboundPayload

data class InboundCommand(
    val appId: String,
    val appToken: String,
    val actorId: String,
    val actorName: String,
    val channel: String,
    val channelName: String,
    val teamId: String? = null,
    val kind: InboundKind,
    val subCommands: List<String> = emptyList(),
    val payload: InboundPayload,
) : IdempotencyData {
    fun extractBasicInfo(idempotencyKey: UUID): CommandBasicInfo =
        CommandBasicInfo(
            appId = appId,
            appToken = appToken,
            publisherId = actorId,
            channel = channel,
            idempotencyKey = idempotencyKey,
        )
}
