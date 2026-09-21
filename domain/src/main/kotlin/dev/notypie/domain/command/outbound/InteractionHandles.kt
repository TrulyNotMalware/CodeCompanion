package dev.notypie.domain.command.outbound

// Maps to Slack's trigger_id, which expires roughly 3 seconds after the interaction.
@JvmInline
value class ModalOpenHandle(
    val raw: String,
)

@JvmInline
value class ResponseReplaceHandle(
    val raw: String,
)
