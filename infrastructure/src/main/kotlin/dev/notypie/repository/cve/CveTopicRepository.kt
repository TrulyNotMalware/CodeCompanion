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
     * Inserts [definition] when no row shares its topicKey; otherwise syncs every field EXCEPT
     * `active` onto the existing row. `active` is owned by chat toggles after the initial insert, so a
     * yaml reboot never silently reactivates a topic an admin deactivated from chat (the yaml `active`
     * only seeds new inserts). Returns true when a row was written, false on a no-op match.
     */
    fun upsert(definition: CveTopicDefinition): Boolean

    /** Topics offered to subscribers (active only), ordered by topicKey. */
    fun findActiveTopics(): List<CveTopic>

    /** Every topic regardless of active flag, ordered by topicKey (admin listing). */
    fun findAllTopics(): List<CveTopic>

    /** The topic with [id] regardless of active flag, or null when it no longer exists. */
    fun findById(id: Long): CveTopic?

    /** Count of active topics (for the ops status report). */
    fun countActive(): Long

    /**
     * Flips [topicKey]'s active flag to [active]. Returns the number of rows updated (0 when no
     * topic has that key).
     */
    fun setActive(topicKey: String, active: Boolean): Int
}
