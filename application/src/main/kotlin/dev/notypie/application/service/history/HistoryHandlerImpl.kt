package dev.notypie.application.service.history

import dev.notypie.domain.history.repository.HistoryRepository
import org.springframework.stereotype.Service

@Service
class HistoryHandlerImpl(
    private val historyRepository: HistoryRepository
): HistoryHandler {

}