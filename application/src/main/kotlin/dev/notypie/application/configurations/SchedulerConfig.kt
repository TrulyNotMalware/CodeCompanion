package dev.notypie.application.configurations

import dev.notypie.application.service.relay.MessageRelayService
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
class SchedulerConfig

//Pooling publisher.
@Component
class PoolingOutboxMessageProcessor(
    private val messageRelayService: MessageRelayService
){
    @Scheduled(fixedRate = 5000)
    fun scheduleDispatch() = this.messageRelayService.dispatchPendingMessages()
}