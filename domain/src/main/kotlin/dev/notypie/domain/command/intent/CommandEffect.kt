package dev.notypie.domain.command.intent

/**
 * Anything a CommandContext can emit for the executor to process: a [CommandIntent] or an
 * OutboundMessage. Not `sealed` because its two implementors live in different packages,
 * which Kotlin's same-package rule for sealed hierarchies forbids.
 */
interface CommandEffect
