package dev.notypie.repository.memory

import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.repository.HistoryRepository

class InMemoryHistoryRepository: HistoryRepository{

    override fun insertNewHistory(history: History) {
        TODO("Not yet implemented")
    }
}