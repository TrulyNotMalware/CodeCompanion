package dev.notypie.application.service.history

import dev.notypie.domain.history.entity.History

interface HistoryHandler {

    fun saveNewHistory(history: History)
}