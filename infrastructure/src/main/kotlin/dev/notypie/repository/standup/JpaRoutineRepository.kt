package dev.notypie.repository.standup

import dev.notypie.repository.standup.schema.RoutineSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
interface JpaRoutineRepository : JpaRepository<RoutineSchema, Long> {
    @Query(
        """
        SELECT r FROM standup_routine r
        LEFT JOIN FETCH r.members
        WHERE r.routineUid = :routineUid
    """,
    )
    fun findByRoutineUid(
        @Param("routineUid") routineUid: UUID,
    ): RoutineSchema?

    @Query(
        """
        SELECT DISTINCT r FROM standup_routine r
        LEFT JOIN FETCH r.members
        WHERE r.commandChannel = :channel
          AND r.isActive = true
    """,
    )
    fun findActiveByCommandChannel(
        @Param("channel") channel: String,
    ): List<RoutineSchema>

    @Query(
        """
        SELECT DISTINCT r FROM standup_routine r
        LEFT JOIN FETCH r.members
        WHERE r.isActive = true
    """,
    )
    fun findAllActive(): List<RoutineSchema>

    /**
     * Soft-delete: flip `is_active` only when the row is currently active. Returning the row
     * count lets callers distinguish "no such routine / already inactive" (0) from "OK" (1)
     * with a single round-trip — same atomic-CAS pattern used elsewhere (see
     * `markMeetingCanceled` in the meeting repository).
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE standup_routine r
        SET r.isActive = false
        WHERE r.routineUid = :routineUid
          AND r.isActive = true
    """,
    )
    fun markInactive(
        @Param("routineUid") routineUid: UUID,
    ): Int
}
