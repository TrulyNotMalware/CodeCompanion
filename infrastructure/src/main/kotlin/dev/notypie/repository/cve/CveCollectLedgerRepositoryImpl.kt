package dev.notypie.repository.cve

import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

open class CveCollectLedgerRepositoryImpl(
    private val jpaCveCollectLedgerRepository: JpaCveCollectLedgerRepository,
) : CveCollectLedgerRepository {
    @Transactional
    override fun claimWindow(topicId: Long, windowStart: LocalDateTime): Boolean =
        jpaCveCollectLedgerRepository.claimWindow(topicId = topicId, windowStart = windowStart) == 1

    @Transactional
    override fun deleteOlderThan(cutoff: LocalDateTime): Int =
        jpaCveCollectLedgerRepository.deleteOlderThan(cutoff = cutoff)
}
