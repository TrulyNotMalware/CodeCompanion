package dev.notypie.repository.cve

import java.time.LocalDateTime

interface CveCollectLedgerRepository {
    fun claimWindow(topicId: Long, windowStart: LocalDateTime): Boolean

    fun deleteOlderThan(cutoff: LocalDateTime): Int

    fun latestWindowStart(): LocalDateTime?
}
