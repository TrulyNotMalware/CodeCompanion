package dev.notypie.application.service.standup

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class StandupScheduler(
    private val schedulingService: StandupSchedulingService,
) {
    // Each phase is isolated: one failing phase used to skip every later phase for every routine on every tick.
    @Scheduled(fixedDelay = 60_000)
    fun tick() {
        runPhase(name = "openSessionsForToday") { schedulingService.openSessionsForToday() }
        runPhase(name = "sendPendingDispatches") { schedulingService.sendPendingDispatches() }
        runPhase(name = "nudgeNonResponders") { schedulingService.nudgeNonResponders() }
        runPhase(name = "detectCutoffs") { schedulingService.detectCutoffs() }
    }

    private fun runPhase(name: String, phase: () -> Unit) {
        runCatching(phase).onFailure { ex ->
            log.error(ex) { "Standup scheduler phase failed: phase=$name" }
        }
    }
}
