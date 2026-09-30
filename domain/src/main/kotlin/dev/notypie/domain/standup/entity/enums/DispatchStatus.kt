package dev.notypie.domain.standup.entity.enums

enum class DispatchStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,

    // Terminal: never sent because the DM would be pointless (session closed / past cutoff, routine inactive).
    SKIPPED,
}
