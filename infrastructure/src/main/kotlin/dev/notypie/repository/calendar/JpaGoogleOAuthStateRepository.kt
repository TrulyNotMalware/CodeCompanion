package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.GoogleOAuthStateSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface JpaGoogleOAuthStateRepository : JpaRepository<GoogleOAuthStateSchema, String> {
    // Atomic single-use claim: a replayed callback with the same state must lose here, not after a read.
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE google_oauth_state
            SET consumed_at = :now
            WHERE state = :state AND consumed_at IS NULL AND expires_at > :now
        """,
        nativeQuery = true,
    )
    fun consume(
        @Param("state") state: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query("DELETE FROM google_oauth_state s WHERE s.expiresAt < :before")
    fun deleteExpiredBefore(
        @Param("before") before: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query("DELETE FROM google_oauth_state s WHERE s.slackUserId = :userId")
    fun deleteBySlackUser(
        @Param("userId") userId: String,
    ): Int
}
