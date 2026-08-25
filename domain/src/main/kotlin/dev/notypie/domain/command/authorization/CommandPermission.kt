package dev.notypie.domain.command.authorization

/**
 * Capability groups gating bot commands. Every command declares the single permission it
 * requires; roles are defined as sets of these.
 */
enum class CommandPermission {
    /** Interactive basics open to everyone: help, forms, modals, slash commands. */
    BASIC,

    /** The AI assistant lane (`ask` and the free-text fallback). */
    AI,

    /** Operational commands exposing internals or pinging others: `status`, `notice`. */
    OPERATIONS,

    /** Role management (`grant`, `revoke`, `roles`) — held by ADMIN only. */
    ADMINISTRATION,
}
