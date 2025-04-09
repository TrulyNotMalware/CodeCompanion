package dev.notypie.templates.dto

import dev.notypie.domain.command.dto.interactions.RejectReason
import java.time.LocalDateTime

data class TimeScheduleAlertContents(
    val title: String = "Time Schedule Notice",
    val description: String = "A new schedule has been registered.\nIf you are unable to attend the schedule below, please press the *Reject* button.\n" +
            "If there is no response after 10 minutes, the attendance will be processed automatically.",
    val startTime: LocalDateTime,
    val host: String,
    val rejectReasons: Set<String> =
        RejectReason.entries.map { it.showMessage }
            .toSet()
)