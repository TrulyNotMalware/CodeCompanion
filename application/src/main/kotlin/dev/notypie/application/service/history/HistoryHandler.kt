package dev.notypie.application.service.history

import dev.notypie.domain.history.entity.History
import java.util.UUID

interface HistoryHandler {

    fun saveNewHistory(history: History)
    fun getHistory(id: UUID): History
}