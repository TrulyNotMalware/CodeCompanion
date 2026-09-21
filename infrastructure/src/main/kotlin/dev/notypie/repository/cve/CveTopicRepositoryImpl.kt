package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.transaction.annotation.Transactional

open class CveTopicRepositoryImpl(
    private val jpaCveTopicRepository: JpaCveTopicRepository,
) : CveTopicRepository {
    @Transactional
    override fun upsert(definition: CveTopicDefinition): Boolean {
        val existing = jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey)
        if (existing == null) {
            jpaCveTopicRepository.save(
                CveTopicSchema(
                    topicKey = definition.topicKey,
                    displayName = definition.displayName,
                    category = definition.category,
                    sourceType = definition.sourceType,
                    sourceConfig = definition.sourceConfig,
                    deliveryMode = definition.deliveryMode,
                    active = definition.active,
                ),
            )
            return true
        }
        if (matches(schema = existing, definition = definition)) return false
        // active is never overwritten here — a yaml reboot must not undo a chat activate|deactivate toggle.
        existing.displayName = definition.displayName
        existing.category = definition.category
        existing.sourceType = definition.sourceType
        existing.sourceConfig = definition.sourceConfig
        existing.deliveryMode = definition.deliveryMode
        jpaCveTopicRepository.save(existing)
        return true
    }

    override fun findActiveTopics(): List<CveTopic> =
        jpaCveTopicRepository.findByActiveTrueOrderByTopicKey().map { toRecord(schema = it) }

    override fun findAllTopics(): List<CveTopic> =
        jpaCveTopicRepository.findAllOrderByTopicKey().map { toRecord(schema = it) }

    override fun findById(id: Long): CveTopic? =
        jpaCveTopicRepository.findById(id).map { toRecord(schema = it) }.orElse(null)

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
