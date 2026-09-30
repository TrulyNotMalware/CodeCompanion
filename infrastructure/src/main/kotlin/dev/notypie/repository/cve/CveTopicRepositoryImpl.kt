package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional

open class CveTopicRepositoryImpl(
    private val jpaCveTopicRepository: JpaCveTopicRepository,
) : CveTopicRepository {
    // Deliberately not @Transactional. Two replicas booting together both find no row and both insert; the
    // loser's uk_cve_topic_topic_key violation used to escape the ApplicationReadyEvent listener and fail the
    // pod. With each JPA call in its own transaction the lost insert rolls back alone, and the winner's row is
    // re-read and synced like any existing row (a violation with no row behind it is not a race and rethrows).
    override fun upsert(definition: CveTopicDefinition): Boolean {
        val existing = jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey)
        if (existing != null) return sync(existing = existing, definition = definition)
        try {
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
        } catch (ex: DataIntegrityViolationException) {
            val raced = jpaCveTopicRepository.findByTopicKey(topicKey = definition.topicKey) ?: throw ex
            return sync(existing = raced, definition = definition)
        }
    }

    private fun sync(existing: CveTopicSchema, definition: CveTopicDefinition): Boolean {
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
