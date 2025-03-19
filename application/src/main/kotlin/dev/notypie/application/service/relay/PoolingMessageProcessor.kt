package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.scheduling.annotation.Scheduled

//Pooling publisher.
class PoolingMessageProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val messageRelayService: SlackMessageRelayServiceImpl
): MessageProcessor{
    @Scheduled(fixedRate = 5000)
    fun scheduleDispatch() = this.getPendingMessages()

    override fun getPendingMessages() {
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