package dev.notypie.repository.cve.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

/**
 * One subscription of a Slack user to a topic. User identity is the raw Slack user
 * id string — no FK user table exists in this schema, matching the rest of the app.
 */
@Entity(name = "cve_subscription")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cve_subscription_user_topic", columnNames = ["user_id", "topic_id"]),
    ],
    indexes = [
        Index(name = "idx_cve_subscription_topic_id", columnList = "topic_id"),
    ],
)
class CveSubscriptionSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "user_id", nullable = false, length = 64)
    val userId: String,
    @field:Column(name = "topic_id", nullable = false)
    val topicId: Long,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
