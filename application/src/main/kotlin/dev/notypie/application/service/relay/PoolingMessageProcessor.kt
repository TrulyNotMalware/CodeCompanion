package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.MessageProcessorParameter
import dev.notypie.application.service.relay.dto.NoParameter
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled

//Pooling publisher.
class PoolingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl
): MessageProcessor{
    @Scheduled(fixedRate = 5000)
    fun scheduleDispatch() = this.getPendingMessages(
        messageParameter = NoParameter
    )

    override fun getPendingMessages(messageParameter: MessageProcessorParameter) {
        val pageSize = 100
        var offset = 0
        while (true) {
            val pendingMessages = this.outboxRepository.findPendingMessages(
                limit = pageSize,
                offset = offset
            )
            if (pendingMessages.isEmpty()) break

            this.messageRelayService.batchPendingMessages(
                pendingMessages = pendingMessages
            )
            offset += pendingMessages.size
        }
    }
}