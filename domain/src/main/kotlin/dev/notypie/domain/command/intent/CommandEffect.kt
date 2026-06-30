package dev.notypie.domain.command.intent

/**
 * Anything a CommandContext can emit for the executor to process: a pure CommandIntent
 * (business request / Slack presentation, being decomposed) or an OutboundMessage
 * (transport-neutral outbound effect). The queue carries the union so producers can migrate
 * family-by-family without a big-bang switch.
 *
 * Not `sealed`: its two implementors live in different packages (`command.intent` and
 * `command.outbound`), which Kotlin's same-package rule for sealed hierarchies forbids. The
 * union is closed in practice — only [CommandIntent] and `OutboundMessage` implement it.
 */
interface CommandEffect
