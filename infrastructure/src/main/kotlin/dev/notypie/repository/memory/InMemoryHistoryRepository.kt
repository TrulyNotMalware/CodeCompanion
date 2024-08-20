package dev.notypie.repository.memory

import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.repository.HistoryRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryHistoryRepository(
    private val inMemoryDatabase: ConcurrentHashMap<UUID, History> = ConcurrentHashMap()
): HistoryRepository{

    override fun insertNewHistory(history: History): History {
        this.inMemoryDatabase[history.historyId] = history
        return history
    }

    override fun getHistoryById(id: UUID): History = this.inMemoryDatabase[id] ?: throw RuntimeException("not exists")

    override fun getHistoryByIdempotencyKey(idempotencyKey: String): History =
        this.inMemoryDatabase.values.filter { it.idempotencyKey == idempotencyKey }
            .first()

}