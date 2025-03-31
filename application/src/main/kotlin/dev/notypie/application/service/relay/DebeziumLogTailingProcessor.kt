package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.Envelope
import dev.notypie.application.service.relay.dto.MessageProcessorParameter
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.toOutboxMessage
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.annotation.KafkaListener

class DebeziumLogTailingProcessor(
    private val messageDispatcher: MessageDispatcher,
    private val eventPublisher: ApplicationEventPublisher
): MessageProcessor {

    @KafkaListener(
        topics = ["\${slack.app.mode.cdc.topic}"], containerFactory = "concurrentKafkaListenerContainerFactory",
        properties = [
            "spring.json.use.type.headers:false"
            ,"spring.json.value.default.type=dev.notypie.application.service.relay.dto.Envelope"
        ]
    )
    override fun getPendingMessages(messageParameter: MessageProcessorParameter) {
        val consumerRecord = messageParameter as Envelope
        val outboxMessage = consumerRecord.payload.after?.toMutableMap()?.toOutboxMessage()
        outboxMessage?.takeIf { it.status == MessageStatus.PENDING.name }
                ?.toSlackEvent()
                ?.let { this.messageDispatcher.dispatch(event = it) }
                ?.also { eventPublisher.publishEvent(it.toOutboxUpdateEvent()) }
    }
}