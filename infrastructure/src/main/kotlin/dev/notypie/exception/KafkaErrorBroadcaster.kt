package dev.notypie.exception

import org.springframework.kafka.core.KafkaTemplate

class KafkaErrorBroadcaster(
    val kafkaTemplate: KafkaTemplate<String, Any>,
) : ErrorBroadcaster {
    override fun broadcastError(message: String): Unit = TODO("Not yet implemented")
}
