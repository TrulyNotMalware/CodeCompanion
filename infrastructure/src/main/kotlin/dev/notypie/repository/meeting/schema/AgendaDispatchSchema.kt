package dev.notypie.repository.meeting.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * JPA mapping for the `agenda_dispatch` once-per-day claim ledger. `agendaDate` is the primary
 * key, so claiming a day is a single `INSERT IGNORE` whose affected-row count tells the scheduler
 * whether *this* tick owns today's agenda. There is no mutable state and no claim token — a day is
 * either claimed (row present) or not — which is the entire idempotency guarantee the daily-agenda
 * scheduler relies on.
 */
@Entity(name = "agenda_dispatch")
class AgendaDispatchSchema(
    @field:Id
    @field:Column(name = "agenda_date", nullable = false)
    val agendaDate: LocalDate,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
