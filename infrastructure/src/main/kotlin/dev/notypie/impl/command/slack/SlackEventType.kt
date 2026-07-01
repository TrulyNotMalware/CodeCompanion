package dev.notypie.impl.command.slack

/**
 * Slack Events API transport vocabulary. Purely a boundary concern — the domain consumes the
 * neutral [dev.notypie.domain.command.inbound.InboundKind] instead. `URL_VERIFICATION` is the
 * challenge handshake, `EVENT_CALLBACK` wraps subtype events, and `APP_MENTION` is the only
 * event subtype this app processes.
 */
enum class SlackEventType {
    URL_VERIFICATION,
    EVENT_CALLBACK,
    APP_MENTION,
}
