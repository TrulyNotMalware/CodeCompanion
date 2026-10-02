package dev.notypie.domain.standup.entity.enums

enum class DispatchStatus {
    PENDING,
    SENDING,
    SENT,

    // Terminal. For now also how a skip is stored: FAILED with a failure_reason starting SKIPPED_REASON_PREFIX.
    FAILED,

    // Terminal: never sent because the DM would be pointless (session closed / past cutoff, routine inactive).
    // Readable but not written in this release: the previous release fails on it in Enum.valueOf, so writing it
    // would stop standups after a rollback (review G2). The next release writes it and can roll back to this one.
    SKIPPED,
    ;

    companion object {
        const val SKIPPED_REASON_PREFIX: String = "skipped: "
    }
}
