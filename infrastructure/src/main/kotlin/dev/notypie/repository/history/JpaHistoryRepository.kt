package dev.notypie.repository.history

import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.repository.HistoryRepository
import dev.notypie.repository.history.schema.JpaHistoryEntity
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class JpaHistoryRepository(
    private val jpaHistoryRepository: JpaHistoryEntityRepository
): HistoryRepository {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun insertNewHistory(history: History): History {
        this.jpaHistoryRepository.save(this.toPersistenceEntity(history = history))
        return history
    }

    override fun getHistoryById(id: UUID): History =
        this.toDomainEntity(this.jpaHistoryRepository.findById(id).orElseThrow())

    private fun toDomainEntity(historyEntity: JpaHistoryEntity): History =
        History(
            apiAppId = historyEntity.apiAppId, channel = historyEntity.channel,
            commandType = historyEntity.commandType,
            idempotencyKey = historyEntity.idempotencyKey,
            status = historyEntity.status, publisherId = historyEntity.publisherId, states = listOf(),
            token = historyEntity.token, type = historyEntity.type
        )

    private fun toPersistenceEntity(history: History) =
        JpaHistoryEntity(
            id = history.historyId, apiAppId = history.apiAppId, channel = history.channel,
            commandType = history.commandType, idempotencyKey = history.idempotencyKey, status = history.status,
            publisherId = history.publisherId, token = history.token, type = history.type
        )
}