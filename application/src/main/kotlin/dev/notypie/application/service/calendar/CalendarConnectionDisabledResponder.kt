package dev.notypie.application.service.calendar

import dev.notypie.domain.command.entity.event.CalendarConnectionRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import org.springframework.context.event.EventListener
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class CalendarConnectionDisabledResponder(
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    companion object {
        const val DISABLED_MESSAGE: String = "Google Calendar integration is not enabled on this bot."
    }

    private val replyTemplate = TransactionTemplate(transactionManager)

    @EventListener
    fun handle(event: CalendarConnectionRequestEvent) {
        replyTemplate.executeWithoutResult {
            outboundStager.stageCalendarEphemeral(
                text = DISABLED_MESSAGE,
                basicInfo = event.payload.responseBasicInfo,
                publisher = eventPublisher,
            )
        }
    }
}
