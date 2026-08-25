package dev.notypie.application.service.standup

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class StandupScheduler(
    private val schedulingService: StandupSchedulingService,
) {
    @Scheduled(fixedDelay = 60_000)
    fun tick() {
        runCatching {
            schedulingService.openSessionsForToday()
            schedulingService.sendPendingDispatches()
            schedulingService.nudgeNonResponders()
            schedulingService.detectCutoffs()
        }.onFailure { ex ->
            log.error(ex) { "Standup scheduler tick failed" }
        }
    }
}
