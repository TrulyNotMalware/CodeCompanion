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
        runPhase(name = "openSessionsForToday") { schedulingService.openSessionsForToday() }
        runPhase(name = "sendPendingDispatches") { schedulingService.sendPendingDispatches() }
        runPhase(name = "nudgeNonResponders") { schedulingService.nudgeNonResponders() }
        runPhase(name = "detectCutoffs") { schedulingService.detectCutoffs() }
    }

    private fun runPhase(name: String, phase: () -> Unit) =
        containFailure(onFailure = { ex -> log.error(ex) { "Standup scheduler phase failed: phase=$name" } }) {
            phase()
        }
}
