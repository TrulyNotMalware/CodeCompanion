package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Component
class OutboxRetentionScheduler(
    private val outboxRepository: MessageOutboxRepository,
    appConfig: AppConfig,
    private val clock: Clock,
) {
    private val retention: Duration = Duration.ofDays(appConfig.outbox.retention.days)
    private val batchSize: Int = appConfig.outbox.retention.batchSize

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L)
    fun purge() {
        runCatching { purgeOnce() }
            .onFailure { log.error(it) { "Outbox retention purge failed" } }
    }

    fun purgeOnce(): Int {
        val cutoff = LocalDateTime.now(clock).minus(retention)
        var total = 0
        for (batch in 1..MAX_BATCHES_PER_TICK) {
            val deleted = outboxRepository.deleteTerminalOlderThan(olderThan = cutoff, limit = batchSize)
            total += deleted
            if (deleted < batchSize) break
        }
        if (total > 0) log.info { "Outbox retention purged $total terminal rows older than $cutoff" }
        return total
    }

    private companion object {
        const val MAX_BATCHES_PER_TICK = 20
    }
}
