package dev.notypie.repository.standup

import dev.notypie.repository.standup.schema.StandupSessionSchema
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    // SELECT … FOR UPDATE on the session row alone — the answer/summary handshake (review G5). recordAnswer and the
    // summary both take it before reading the session's state, so an answer either commits before the summary reads
    // the answers or waits and then finds the session SUMMARIZED. Collections stay lazy; load them after the lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT s FROM standup_session s
        WHERE s.sessionUid = :sessionUid
    """,
    )
    fun findLockedBySessionUid(
        @Param("sessionUid") sessionUid: UUID,
    ): StandupSessionSchema?

    // MariaDB-only (H2 needs MODE=MariaDB), on uk_standup_answer_session_user: one statement decides insert or update
    // against the committed rows, where a find-then-insert decides from whatever the transaction last saw (review G4).
    @Modifying
    @Query(
        value = """
            INSERT INTO standup_answer (session_id, user_id, responses, submitted_at)
            VALUES (:sessionId, :userId, :responses, :submittedAt)
            ON DUPLICATE KEY UPDATE responses = :responses, submitted_at = :submittedAt
        """,
        nativeQuery = true,
    )
    fun upsertAnswer(
        @Param("sessionId") sessionId: Long,
        @Param("userId") userId: String,
        @Param("responses") responses: String,
        @Param("submittedAt") submittedAt: Instant,
    ): Int

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

    // Eagerly fetched so the caller can map the full graph outside the persistence context (else Lazy exception).
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

    // Guarded by nudged_at IS NULL AND status = 'COLLECTING' — loosening either lets a session be nudged twice.
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
