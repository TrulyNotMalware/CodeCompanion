package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveSubscriptionSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface JpaCveSubscriptionRepository : JpaRepository<CveSubscriptionSchema, Long> {
    fun findByUserId(userId: String): List<CveSubscriptionSchema>

    fun deleteByUserIdAndTopicIdIn(userId: String, topicIds: List<Long>): Long

    /**
     * Atomically inserts one (user, topic) pair. `INSERT IGNORE` swallows the duplicate-key error
     * when the pair already exists (double-submit or a concurrent instance), so the affected-row
     * count is the insert signal — no check-then-act race against the unique constraint. Mirrors
     * `JpaAgendaDispatchRepository.claimAgenda`.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT IGNORE INTO cve_subscription (user_id, topic_id, created_at)
            VALUES (:userId, :topicId, CURRENT_TIMESTAMP(6))
        """,
        nativeQuery = true,
    )
    fun insertIgnore(
        @Param("userId") userId: String,
        @Param("topicId") topicId: Long,
    ): Int
}
