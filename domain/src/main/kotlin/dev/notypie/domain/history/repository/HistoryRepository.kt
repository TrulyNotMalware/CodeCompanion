package dev.notypie.domain.history.repository

import dev.notypie.domain.history.entity.History

interface HistoryRepository {
    fun insertNewHistory(history: History)
}