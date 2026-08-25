package dev.notypie.repository.standup

import dev.notypie.repository.standup.schema.SessionDispatchSchema
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface JpaSessionDispatchRepository : JpaRepository<SessionDispatchSchema, Long> {
    @Query(
        """
        SELECT d FROM standup_session_dispatch d
        JOIN FETCH d.session
        WHERE d.dmStatus = dev.notypie.domain.standup.entity.enums.DispatchStatus.PENDING
          AND d.dmTriggerAt <= :before
        ORDER BY d.dmTriggerAt ASC
        """,
    )
    fun findPendingBefore(
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<SessionDispatchSchema>

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session_dispatch
            SET dm_status = 'SENDING', claim_token = :token, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND dm_status = 'PENDING'
        """,
        nativeQuery = true,
    )
    fun claimDispatch(
        @Param("id") id: Long,
        @Param("token") token: String,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session_dispatch
            SET dm_status = 'SENT', dm_sent_at = :sentAt, claim_token = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND dm_status = 'SENDING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markSent(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("sentAt") sentAt: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session_dispatch
            SET dm_status = 'FAILED', failure_reason = :reason, claim_token = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND dm_status = 'SENDING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markFailed(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE standup_session_dispatch
            SET dm_status = 'PENDING', claim_token = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE dm_status = 'SENDING' AND updated_at < :olderThan
        """,
        nativeQuery = true,
    )
    fun resetStuckSending(
        @Param("olderThan") olderThan: Instant,
    ): Int
}
