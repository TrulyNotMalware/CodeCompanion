package dev.notypie.application.service.meeting

import dev.notypie.common.containFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class DailyAgendaScheduler(
    private val schedulingService: DailyAgendaSchedulingService,
) {
    @Scheduled(fixedDelay = 60_000)
    fun tick() {
        containFailure(onFailure = { ex -> log.error(ex) { "Daily agenda scheduler tick failed" } }) {
            schedulingService.sendDailyAgenda()
        }
    }
}
