package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

open class CveTopicRepositoryImpl(
    private val jpaCveTopicRepository: JpaCveTopicRepository,
    transactionManager: PlatformTransactionManager,
) : CveTopicRepository {
    // A failed INSERT poisons the session it ran in, so the insert that may lose a replica race gets its own.
    private val insertTemplate: TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    @Transactional
    override fun upsert(definition: CveTopicDefinition): Boolean {
        val existing =
            jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey)
                ?: insertOrLoadRacedRow(definition = definition)
                ?: return true
        if (matches(schema = existing, definition = definition)) return false
        // active is never overwritten here — a yaml reboot must not undo a chat activate|deactivate toggle.
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

    private fun insertOrLoadRacedRow(definition: CveTopicDefinition): CveTopicSchema? =
        try {
            insertTemplate.executeWithoutResult {
                jpaCveTopicRepository.saveAndFlush(
                    newSchema(definition = definition),
                )
            }
            null
        } catch (exception: DataIntegrityViolationException) {
            jpaCveTopicRepository.findLockedByTopicKey(topicKey = definition.topicKey) ?: throw exception
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
