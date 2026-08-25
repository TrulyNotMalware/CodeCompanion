package dev.notypie.domain.command.inbound

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.common.IdempotencyData
import java.util.UUID

/** Neutral kind of an inbound command, decoupled from any specific transport event vocabulary. */
enum class InboundKind {
    SLASH,
    MENTION,
    INTERACTION,
}

/** Transport-neutral payload carried by an [InboundCommand]. Domain routes on the concrete type. */
sealed interface InboundPayload

/** A slash invocation. Only the trigger handle is consumed downstream (to open a modal). */
data class SlashInvocation(
    val trigger: TriggerHandle,
) : InboundPayload

/**
 * An app-mention invocation, already flattened by the inbound adapter: mention tokens are
 * bot-filtered and command text is split/blank-filtered in the infra mapper. [hasCommandStructure]
 * is true iff a command-bearing structure (a rich_text_section) was found — it distinguishes the
 * "not supported" outcome (no structure) from the "empty command" outcome (structure, zero tokens).
 *
 * [message] identifies the mention message itself and [thread] its enclosing thread root (null for
 * a top-level mention). Together they anchor conversational features — a threaded reply targets
 * `thread ?: message`, which also serves as the stable conversation id across follow-up mentions.
 */
data class MentionInvocation(
    val mentionedUserIds: List<String>,
    val commandTokens: List<String>,
    val hasCommandStructure: Boolean,
    val message: MessageHandle? = null,
    val thread: MessageHandle? = null,
) : InboundPayload

/** Transport-neutral inbound command envelope consumed by the domain command pipeline. */
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
