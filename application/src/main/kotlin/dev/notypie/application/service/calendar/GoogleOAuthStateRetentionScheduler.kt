package dev.notypie.application.service.calendar

import dev.notypie.application.configurations.conditions.OnGoogleCalendarEnabled
import dev.notypie.application.service.relay.purgeInBatches
import dev.notypie.common.containFailure
import dev.notypie.repository.calendar.GoogleOAuthStateRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

private val log = KotlinLogging.logger {}

internal const val OAUTH_STATE_RETENTION_PERIOD_MILLIS = 3_600_000L
internal const val OAUTH_STATE_RETENTION_FIRST_RUN_MIN_MILLIS = 600_000L
internal const val OAUTH_STATE_RETENTION_FIRST_RUN_MAX_MILLIS = 1_200_000L
internal const val OAUTH_STATE_RETENTION_BATCH_SIZE = 1_000

@Component
@Conditional(OnGoogleCalendarEnabled::class)
class GoogleOAuthStateRetentionScheduler(
    private val stateRepository: GoogleOAuthStateRepository,
    private val clock: Clock,
) {
    @Scheduled(
        fixedDelay = OAUTH_STATE_RETENTION_PERIOD_MILLIS,
        initialDelayString =
            "\${random.long[$OAUTH_STATE_RETENTION_FIRST_RUN_MIN_MILLIS,$OAUTH_STATE_RETENTION_FIRST_RUN_MAX_MILLIS]}",
    )
    fun purge() {
        purgeOnce()
    }

    fun purgeOnce(): Int {
        val now = clock.instant()
        var total = 0
        containFailure(
            onFailure = { exception ->
                log.error(exception) { "OAuth state retention purge of google_oauth_state failed after $total rows" }
            },
        ) {
            purgeInBatches(batchSize = OAUTH_STATE_RETENTION_BATCH_SIZE) { limit ->
                stateRepository.deleteExpired(before = now, limit = limit).also { deleted -> total += deleted }
            }
        }
        if (total > 0) log.info { "OAuth state retention purged $total rows of google_oauth_state expired before $now" }
        return total
    }
}
