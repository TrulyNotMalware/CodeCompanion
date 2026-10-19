package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentAskTurnSchema
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

@Repository
interface AgentAskTurnRepository : JpaRepository<AgentAskTurnSchema, String> {
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE agent_ask_turn
        SET status = 'IN_PROGRESS', attempt_count = attempt_count + 1, run_count = run_count + 1, updated_at = :now
        WHERE event_id = :eventId
          AND status = 'PENDING'
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun begin(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE agent_ask_turn
        SET status = 'PENDING', run_count = GREATEST(run_count - 1, 0), updated_at = :now
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun release(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE agent_ask_turn
        SET status = 'PENDING', updated_at = :now
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
          AND updated_at < :orphanedBefore
    """,
        nativeQuery = true,
    )
    fun releaseOrphan(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("orphanedBefore") orphanedBefore: LocalDateTime,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE agent_ask_turn
        SET status = :status, updated_at = :now
        WHERE event_id = :eventId
          AND status IN ('PENDING', 'IN_PROGRESS')
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun finish(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("status") status: String,
        @Param("now") now: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT * FROM agent_ask_turn
        WHERE (status = 'PENDING' AND updated_at < :pendingBefore)
           OR (status = 'IN_PROGRESS' AND updated_at < :orphanedBefore)
        ORDER BY updated_at ASC
        LIMIT :limit
    """,
        nativeQuery = true,
    )
    fun findResumable(
        @Param("pendingBefore") pendingBefore: LocalDateTime,
        @Param("orphanedBefore") orphanedBefore: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<AgentAskTurnSchema>

    @Modifying
    @Transactional
    @Query(
        """
        DELETE FROM agent_ask_turn
        WHERE status IN ('COMPLETED', 'ABANDONED')
          AND updated_at < :olderThan
        LIMIT :limit
    """,
        nativeQuery = true,
    )
    fun deleteFinishedOlderThan(
        @Param("olderThan") olderThan: LocalDateTime,
        @Param("limit") limit: Int,
    ): Int
}

data class RecordedAskTurn(
    val turn: AgentAskTurnSchema,
    val storedNow: Boolean,
)

fun AgentAskTurnRepository.recordDelivered(eventId: String, messageTs: String, now: LocalDateTime): RecordedAskTurn {
    check(!TransactionSynchronizationManager.isActualTransactionActive()) {
        "AgentAskTurnRepository.recordDelivered must run outside a transaction: inside one a refused insert leaves " +
            "its new instance in the persistence context, and the read that follows returns that instance instead of " +
            "the stored row (eventId=$eventId)"
    }
    return try {
        RecordedAskTurn(
            turn =
                saveAndFlush(
                    AgentAskTurnSchema(eventId = eventId, messageTs = messageTs, createdAt = now, updatedAt = now),
                ),
            storedNow = true,
        )
    } catch (duplicate: DataIntegrityViolationException) {
        RecordedAskTurn(turn = findByIdOrNull(id = eventId) ?: throw duplicate, storedNow = false)
    }
}
