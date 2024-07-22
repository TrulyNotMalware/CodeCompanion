package dev.notypie.application.service.history

import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.repository.HistoryRepository
import org.springframework.stereotype.Service

@Service
class HistoryHandlerImpl(
    private val historyRepository: HistoryRepository
): HistoryHandler {

    fun updateHistory(slackApiResponse: SlackApiResponse){

    }

    override fun saveNewHistory(history: History) {
        this.historyRepository.insertNewHistory(history = history)
    }
}