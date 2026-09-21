package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveCollectLedgerSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
interface JpaCveCollectLedgerRepository : JpaRepository<CveCollectLedgerSchema, Long> {
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT IGNORE INTO cve_collect_ledger (topic_id, window_start, created_at)
            VALUES (:topicId, :windowStart, CURRENT_TIMESTAMP(6))
        """,
        nativeQuery = true,
    )
    fun claimWindow(
        @Param("topicId") topicId: Long,
        @Param("windowStart") windowStart: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query("DELETE FROM cve_collect_ledger l WHERE l.windowStart < :cutoff")
    fun deleteOlderThan(
        @Param("cutoff") cutoff: LocalDateTime,
    ): Int

    @Query("SELECT MAX(l.windowStart) FROM cve_collect_ledger l")
    fun findLatestWindowStart(): LocalDateTime?
}
