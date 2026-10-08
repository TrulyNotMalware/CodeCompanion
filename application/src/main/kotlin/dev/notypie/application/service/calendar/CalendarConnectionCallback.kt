package dev.notypie.application.service.calendar

enum class CalendarConnectionOutcome {
    CONNECTED,
    DENIED,
    SCOPE_DENIED,
    INVALID_STATE,
    EXCHANGE_FAILED,
}

interface CalendarConnectionCallback {
    fun completeConnection(code: String?, state: String?, error: String?): CalendarConnectionOutcome
}
