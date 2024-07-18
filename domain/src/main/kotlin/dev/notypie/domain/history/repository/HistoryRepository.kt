package dev.notypie.domain.history.repository

import dev.notypie.domain.history.entity.History
import java.util.UUID

interface HistoryRepository {
    fun insertNewHistory(history: History): History
    fun getHistoryById(id: UUID): History
}