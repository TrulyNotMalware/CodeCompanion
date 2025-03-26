package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.Envelope
import dev.notypie.application.service.relay.dto.MessageProcessorParameter
import org.springframework.kafka.annotation.KafkaListener

class DebeziumLogTailingProcessor(
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

    }
}