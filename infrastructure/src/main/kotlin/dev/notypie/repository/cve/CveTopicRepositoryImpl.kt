package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

open class CveTopicRepositoryImpl(
    private val jpaCveTopicRepository: JpaCveTopicRepository,
    transactionManager: PlatformTransactionManager,
) : CveTopicRepository {
    private val syncTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val insertTemplate: TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun upsert(definition: CveTopicDefinition): Boolean {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "CveTopicRepository.upsert must run outside a transaction: it opens its own so that the locked read is " +
                "each one's first statement, and its REQUIRES_NEW insert would wait on a gap lock the caller holds " +
                "(topicKey=${definition.topicKey})"
        }
        return syncExisting(definition = definition) ?: insertOrSyncRacedRow(definition = definition)
    }

    private fun syncExisting(definition: CveTopicDefinition): Boolean? =
        syncTemplate.execute {
            jpaCveTopicRepository
                .findLockedByTopicKey(topicKey = definition.topicKey)
                ?.let { existing -> sync(existing = existing, definition = definition) }
        }

    private fun insertOrSyncRacedRow(definition: CveTopicDefinition): Boolean =
        try {
            insertTemplate.executeWithoutResult {
                jpaCveTopicRepository.saveAndFlush(
                    newSchema(definition = definition),
                )
            }
            true
        } catch (exception: DataIntegrityViolationException) {
            syncExisting(definition = definition) ?: throw exception
        }

    private fun sync(existing: CveTopicSchema, definition: CveTopicDefinition): Boolean {
        if (matches(schema = existing, definition = definition)) return false
        existing.redefine(
            displayName = definition.displayName,
            category = definition.category,
            sourceType = definition.sourceType,
            sourceConfig = definition.sourceConfig,
            deliveryMode = definition.deliveryMode,
        )
        jpaCveTopicRepository.save(existing)
        return true
    }

    private fun newSchema(definition: CveTopicDefinition): CveTopicSchema =
        CveTopicSchema(
            topicKey = definition.topicKey,
            displayName = definition.displayName,
            category = definition.category,
            sourceType = definition.sourceType,
            sourceConfig = definition.sourceConfig,
            deliveryMode = definition.deliveryMode,
            active = definition.active,
        )

    @Transactional(readOnly = true)
    override fun findActiveTopics(): List<CveTopic> =
        jpaCveTopicRepository.findByActiveTrueOrderByTopicKey().map { toRecord(schema = it) }

    @Transactional(readOnly = true)
    override fun findAllTopics(): List<CveTopic> =
        jpaCveTopicRepository.findAllOrderByTopicKey().map { toRecord(schema = it) }

    @Transactional(readOnly = true)
    override fun findById(id: Long): CveTopic? = jpaCveTopicRepository.findByIdOrNull(id)?.let { toRecord(schema = it) }

    @Transactional(readOnly = true)
    override fun countActive(): Long = jpaCveTopicRepository.countActive()

    @Transactional
    override fun setActive(topicKey: String, active: Boolean): Int =
        jpaCveTopicRepository.setActive(topicKey = topicKey, active = active)

    private fun matches(schema: CveTopicSchema, definition: CveTopicDefinition): Boolean =
        schema.displayName == definition.displayName &&
            schema.category == definition.category &&
            schema.sourceType == definition.sourceType &&
            schema.sourceConfig == definition.sourceConfig &&
            schema.deliveryMode == definition.deliveryMode

    private fun toRecord(schema: CveTopicSchema): CveTopic =
        CveTopic(
            id = schema.id,
            topicKey = schema.topicKey,
            displayName = schema.displayName,
            category = schema.category,
            sourceType = schema.sourceType,
            sourceConfig = schema.sourceConfig,
            deliveryMode = schema.deliveryMode,
            active = schema.active,
        )
}
