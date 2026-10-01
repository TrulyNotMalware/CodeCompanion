package dev.notypie.repository.cve.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class CveTopicCategory { LANGUAGE, FRAMEWORK, CVE, ETC }

enum class CveSourceType { GITHUB_RELEASE, NVD_CVE, RSS }

enum class CveDeliveryMode { IMMEDIATE, DIGEST }

// Only changed columns go into an UPDATE: a bootstrap upsert holding a stale row must not rewrite `active`.
@DynamicUpdate
@Entity(name = "cve_topic")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cve_topic_topic_key", columnNames = ["topic_key"]),
    ],
)
class CveTopicSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "topic_key", nullable = false, length = 64)
    val topicKey: String,
    displayName: String,
    category: CveTopicCategory,
    sourceType: CveSourceType,
    sourceConfig: String? = null,
    deliveryMode: CveDeliveryMode,
    active: Boolean = true,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
) {
    @field:Column(name = "display_name", nullable = false, length = 128)
    var displayName: String = displayName
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "category", nullable = false, length = 16)
    var category: CveTopicCategory = category
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "source_type", nullable = false, length = 32)
    var sourceType: CveSourceType = sourceType
        protected set

    @field:Column(name = "source_config", columnDefinition = "TEXT")
    var sourceConfig: String? = sourceConfig
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "delivery_mode", nullable = false, length = 16)
    var deliveryMode: CveDeliveryMode = deliveryMode
        protected set

    @field:Column(name = "active", nullable = false)
    var active: Boolean = active
        protected set

    fun redefine(
        displayName: String,
        category: CveTopicCategory,
        sourceType: CveSourceType,
        sourceConfig: String?,
        deliveryMode: CveDeliveryMode,
    ) {
        this.displayName = displayName
        this.category = category
        this.sourceType = sourceType
        this.sourceConfig = sourceConfig
        this.deliveryMode = deliveryMode
    }
}
