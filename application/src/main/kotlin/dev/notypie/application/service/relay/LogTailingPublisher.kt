package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import org.springframework.kafka.annotation.KafkaListener

class LogTailingPublisher(
    private val appConfig: AppConfig
): MessageProcessor {

    @KafkaListener(topics = ["#{appConfig.mode.cdc.topic}"], containerFactory = "concurrentKafkaListenerContainerFactory")
    override fun getPendingMessages() {

    }
}