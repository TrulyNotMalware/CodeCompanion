package dev.notypie.repository.meeting.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(name = "agenda_dispatch")
class AgendaDispatchSchema(
    @field:Id
    @field:Column(name = "agenda_date", nullable = false)
    val agendaDate: LocalDate,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
