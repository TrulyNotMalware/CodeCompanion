package dev.notypie.application.service.meeting

import java.time.LocalDateTime

data class AgendaItem(
    val startAt: LocalDateTime,
    val title: String,
)
