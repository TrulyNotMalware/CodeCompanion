package dev.notypie.repository.standup

import dev.notypie.repository.standup.schema.StandupSessionSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
interface JpaStandupSessionRepository : JpaRepository<StandupSessionSchema, Long> {
    /**
     * Returns the session for a routine on a specific date with dispatches and answers
     * eagerly fetched. The unique constraint `(routine_uid, session_date)` guarantees at
     * most one row.
     */
    @Query(
        """
        SELECT DISTINCT s FROM standup_session s
        LEFT JOIN FETCH s.dispatches
        LEFT JOIN FETCH s.answers
        WHERE s.routineUid = :routineUid
          AND s.sessionDate = :sessionDate
    """,
    )
    fun findByRoutineUidAndSessionDate(
        @Param("routineUid") routineUid: UUID,
        @Param("sessionDate") sessionDate: LocalDate,
    ): StandupSessionSchema?

    @Query(
        """
        SELECT s FROM standup_session s
        WHERE s.routineUid = :routineUid
          AND s.sessionDate = :sessionDate
    """,
    )
    fun findShallowByRoutineUidAndSessionDate(
        @Param("routineUid") routineUid: UUID,
        @Param("sessionDate") sessionDate: LocalDate,
    ): StandupSessionSchema?

    @Query(
        """
        SELECT DISTINCT s FROM standup_session s
        LEFT JOIN FETCH s.dispatches
        LEFT JOIN FETCH s.answers
        WHERE s.sessionUid = :sessionUid
    """,
    )
    fun findBySessionUid(
        @Param("sessionUid") sessionUid: UUID,
    ): StandupSessionSchema?

    // Dispatches and answers are eagerly fetched so the caller can map the full session graph
    // (toStandupSessionDto) outside the persistence context without a LazyInitializationException.
    @Query(
        """
        SELECT DISTINCT s FROM standup_session s
        LEFT JOIN FETCH s.dispatches
        LEFT JOIN FETCH s.answers
        WHERE s.status = dev.notypie.domain.standup.entity.enums.SessionStatus.COLLECTING
          AND s.cutoffAt <= :before
    """,
    )
    fun findCollectingPastCutoff(
        @Param("before") before: Instant,
    ): List<StandupSessionSchema>

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session
            SET status = 'SUMMARIZED', summary_message_ts = :messageTs, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'COLLECTING'
        """,
        nativeQuery = true,
    )
    fun markSummarized(
        @Param("id") id: Long,
        @Param("messageTs") messageTs: String,
    ): Int

    /**
     * COLLECTING sessions inside the nudge window — cutoff still ahead of `now` but reached
     * within `nudgeWindowEnd`, and not yet nudged. Dispatches and answers are eagerly fetched
     * so the scheduler can compute non-responders without re-loading the session graph.
     */
    @Query(
        """
        SELECT DISTINCT s FROM standup_session s
        LEFT JOIN FETCH s.dispatches
        LEFT JOIN FETCH s.answers
        WHERE s.status = dev.notypie.domain.standup.entity.enums.SessionStatus.COLLECTING
          AND s.nudgedAt IS NULL
          AND s.cutoffAt > :now
          AND s.cutoffAt <= :nudgeWindowEnd
    """,
    )
    fun findCollectingForNudge(
        @Param("now") now: Instant,
        @Param("nudgeWindowEnd") nudgeWindowEnd: Instant,
    ): List<StandupSessionSchema>

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session
            SET nudged_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND nudged_at IS NULL AND status = 'COLLECTING'
        """,
        nativeQuery = true,
    )
    fun claimNudge(
        @Param("id") id: Long,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session
            SET summary_message_ts = :messageTs, updated_at = CURRENT_TIMESTAMP
            WHERE summary_message_ts = :currentMessageTs
              AND status = 'SUMMARIZED'
        """,
        nativeQuery = true,
    )
    fun replaceSummaryMessageTs(
        @Param("currentMessageTs") currentMessageTs: String,
        @Param("messageTs") messageTs: String,
    ): Int
}
