package dev.notypie.application.service.meeting

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class MeetingReminderScheduler(
    private val schedulingService: MeetingReminderSchedulingService,
) {
    @Scheduled(fixedDelay = 60_000)
    fun tick() {
        runCatching {
            schedulingService.materializeReminders()
            schedulingService.sendDueReminders()
        }.onFailure { ex ->
            log.error(ex) { "Meeting reminder scheduler tick failed" }
        }
    }
}
