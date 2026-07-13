package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.repository.cve.schema.CveTopicCategory

data class CveTopic(
    val id: Long,
    val topicKey: String,
    val displayName: String,
    val category: CveTopicCategory,
    val sourceType: CveSourceType,
    val sourceConfig: String?,
    val deliveryMode: CveDeliveryMode,
    val active: Boolean,
)

data class CveTopicDefinition(
    val topicKey: String,
    val displayName: String,
    val category: CveTopicCategory,
    val sourceType: CveSourceType,
    val sourceConfig: String? = null,
    val deliveryMode: CveDeliveryMode,
    val active: Boolean = true,
)

interface CveTopicRepository {
    /**
     * Inserts [definition] or updates the existing row with the same topicKey when any
     * field differs. Returns true when a row was written, false on a no-op match.
     */
    fun upsert(definition: CveTopicDefinition): Boolean

    /** Topics offered to subscribers (active only), ordered by topicKey. */
    fun findActiveTopics(): List<CveTopic>
}
