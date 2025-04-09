package dev.notypie.domain.command.dto.interactions

enum class RejectReason(
    val showMessage: String
) {
    ATTENDING("Attending"),

    SCHEDULE_CONFLICT("Schedule conflict"),
    UNEXPECTED_EMERGENCY("Unexpected emergency"),
    HEALTH_ISSUE("Health issue"),
    PRIOR_COMMITMENT("Prior commitment"),
    REQUEST_DELAY("Delay request"),
    VACATION("Vacation"),
    PERSONAL_REASON("PERSONAL reason"),
    OTHER("Other")
}