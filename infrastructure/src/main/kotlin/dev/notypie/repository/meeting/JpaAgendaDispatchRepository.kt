package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.AgendaDispatchSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Repository
interface JpaAgendaDispatchRepository : JpaRepository<AgendaDispatchSchema, LocalDate> {
    /**
     * Atomically claims the agenda for [date]. `INSERT IGNORE` swallows the duplicate-key error
     * when another tick already inserted the row, so the affected-row count is the claim signal:
     * `1` means *this* call owns today's agenda, `0` means it was already claimed. Mirrors the
     * native `@Modifying @Transactional @Query` CAS style used by `JpaMeetingReminderRepository`.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT IGNORE INTO agenda_dispatch (agenda_date, created_at)
            VALUES (:date, CURRENT_TIMESTAMP)
        """,
        nativeQuery = true,
    )
    fun claimAgenda(
        @Param("date") date: LocalDate,
    ): Int
}
