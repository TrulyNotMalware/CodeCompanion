package dev.notypie.application.service.meeting

import java.time.LocalDateTime

/**
 * One line of a user's daily agenda DM: a meeting they are attending today, carrying just the
 * start time and title needed to render `• HH:mm — Title`.
 */
data class AgendaItem(
    val startAt: LocalDateTime,
    val title: String,
)
