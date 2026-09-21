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
    fun upsert(definition: CveTopicDefinition): Boolean

    fun findActiveTopics(): List<CveTopic>

    fun findAllTopics(): List<CveTopic>

    fun findById(id: Long): CveTopic?

    fun countActive(): Long

    fun setActive(topicKey: String, active: Boolean): Int
}
