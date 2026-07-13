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
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class CveTopicCategory { LANGUAGE, FRAMEWORK, CVE, ETC }

enum class CveSourceType { GITHUB_RELEASE, NVD_CVE, RSS }

enum class CveDeliveryMode { IMMEDIATE, DIGEST }

/**
 * Admin-managed subscription topic. Rows are upserted from yaml at boot (keyed by
 * [topicKey]); rows absent from the config are left untouched, so runtime-added
 * topics survive restarts.
 */
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
    @field:Column(name = "display_name", nullable = false, length = 128)
    var displayName: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "category", nullable = false, length = 16)
    var category: CveTopicCategory,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "source_type", nullable = false, length = 32)
    var sourceType: CveSourceType,
    @field:Column(name = "source_config", columnDefinition = "TEXT")
    var sourceConfig: String? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "delivery_mode", nullable = false, length = 16)
    var deliveryMode: CveDeliveryMode,
    @field:Column(name = "active", nullable = false)
    var active: Boolean = true,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)
