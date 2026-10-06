package dev.notypie.domain.standup.entity.enums

enum class DispatchStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    ;

    companion object {
        const val SKIPPED_REASON_PREFIX: String = "skipped: "
    }
}
