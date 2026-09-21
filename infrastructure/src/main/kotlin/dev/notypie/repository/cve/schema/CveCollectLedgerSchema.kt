package dev.notypie.repository.cve.schema

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity(name = "cve_collect_ledger")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cve_collect_ledger_topic_window", columnNames = ["topic_id", "window_start"]),
    ],
)
class CveCollectLedgerSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "topic_id", nullable = false)
    val topicId: Long,
    @field:Column(name = "window_start", nullable = false)
    val windowStart: LocalDateTime,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
