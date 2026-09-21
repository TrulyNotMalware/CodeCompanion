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
