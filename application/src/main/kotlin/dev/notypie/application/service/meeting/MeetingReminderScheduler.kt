package dev.notypie.application.service.meeting

import dev.notypie.application.service.standup.containFailure
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
        containFailure(onFailure = { ex -> log.error(ex) { "Meeting reminder materialize phase failed" } }) {
            schedulingService.materializeReminders()
        }
        containFailure(onFailure = { ex -> log.error(ex) { "Meeting reminder send phase failed" } }) {
            schedulingService.sendDueReminders()
        }
    }
}
