package dev.notypie.domain.meet.entity.enums

// SENDING is claimed via atomic CAS to prevent duplicate dispatch on concurrent scheduler runs.
enum class MeetingReminderStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
}
