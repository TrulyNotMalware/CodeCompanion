package dev.notypie.domain.command.outbound

/**
 * Opaque handle for opening a modal. The domain never inspects the raw value; only the eventual
 * Slack stager unpacks it. On Slack this maps to a `trigger_id`, which expires roughly 3 seconds
 * after the originating interaction.
 */
@JvmInline
value class ModalOpenHandle(
    val raw: String,
)

/**
 * Opaque handle for replacing an already-delivered message. The domain never inspects the raw
 * value; only the eventual Slack stager unpacks it. On Slack this maps to a `response_url`.
 */
@JvmInline
value class ResponseReplaceHandle(
    val raw: String,
)
