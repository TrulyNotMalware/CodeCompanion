package dev.notypie.application.service.history

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.repository.HistoryRepository
import org.springframework.stereotype.Service
import java.util.*

@Service
class HistoryHandlerImpl(
    private val historyRepository: HistoryRepository
): HistoryHandler {

    fun updateHistory(slackApiResponse: CommandOutput){

    }

    override fun saveNewHistory(history: History) {
        this.historyRepository.insertNewHistory(history = history)
    }

    override fun getHistory(id: UUID): History =
        this.historyRepository.getHistoryById(id = id)
}