package dev.notypie.repository.cve.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

enum class CveDeliveryStatus { SENT, FAILED }

/**
 * Per-(event, user) delivery ledger. The outbox retries transport but does not
 * deduplicate, so this unique key is what prevents a subscriber from receiving
 * the same event twice.
 */
@Entity(name = "cve_delivery")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cve_delivery_event_user", columnNames = ["event_id", "user_id"]),
    ],
    indexes = [
        Index(name = "idx_cve_delivery_user_id", columnList = "user_id"),
    ],
)
class CveDeliverySchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "event_id", nullable = false)
    val eventId: Long,
    @field:Column(name = "user_id", nullable = false, length = 64)
    val userId: String,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 16)
    var status: CveDeliveryStatus,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
