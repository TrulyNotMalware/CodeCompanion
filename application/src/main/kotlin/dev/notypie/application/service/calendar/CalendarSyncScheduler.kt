package dev.notypie.application.service.calendar

import dev.notypie.application.configurations.conditions.OnGoogleCalendarEnabled
import dev.notypie.application.service.standup.containFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
@Conditional(OnGoogleCalendarEnabled::class)
class CalendarSyncScheduler(
    private val syncService: CalendarSyncService,
) {
    @Scheduled(fixedDelay = 60_000)
    fun tick() {
        containFailure(onFailure = { exception -> log.error(exception) { "Google Calendar sync tick failed" } }) {
            syncService.syncDue()
        }
    }
}
