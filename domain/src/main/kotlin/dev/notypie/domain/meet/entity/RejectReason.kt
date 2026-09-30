package dev.notypie.domain.meet.entity

enum class RejectReason(
    val showMessage: String,
) {
    ATTENDING("Attending"),

    SCHEDULE_CONFLICT("Schedule conflict"),
    UNEXPECTED_EMERGENCY("Unexpected emergency"),
    HEALTH_ISSUE("Health issue"),
    PRIOR_COMMITMENT("Prior commitment"),
    REQUEST_DELAY("Delay request"),
    VACATION("Vacation"),
    PERSONAL_REASON("PERSONAL reason"),
    OTHER("Other"),
    ;

    companion object {
        // Longest free-text "Other" note that is stored: meeting_participants.absent_reason_detail is VARCHAR(255)
        // (V8) and the JPA mapping takes its length from here. A longer note would fail the whole decline at the DB.
        const val MAX_DETAIL_LENGTH: Int = 255
    }
}
