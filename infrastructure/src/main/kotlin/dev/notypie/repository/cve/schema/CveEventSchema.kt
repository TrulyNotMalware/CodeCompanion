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
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class CveSummaryStatus { PENDING, SUMMARIZING, DONE, FAILED }

/**
 * One ingested source event (release, CVE advisory, ...). unique(topic_id, external_id)
 * makes collection idempotent. The AI summary is produced exactly once by a worker
 * claiming PENDING/FAILED rows via [claimToken] CAS; reads (/latest, digests, resends)
 * reuse [aiSummary] with zero AI calls.
 */
@Entity(name = "cve_event")
@Table(
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cve_event_topic_external", columnNames = ["topic_id", "external_id"]),
    ],
    indexes = [
        Index(name = "idx_cve_event_summary_status", columnList = "summary_status"),
    ],
)
class CveEventSchema(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    val id: Long = 0,
    @field:Column(name = "topic_id", nullable = false)
    val topicId: Long,
    @field:Column(name = "external_id", nullable = false, length = 255)
    val externalId: String,
    @field:Column(name = "title", nullable = false, length = 512)
    val title: String,
    @field:Column(name = "raw_content", nullable = false, columnDefinition = "TEXT")
    val rawContent: String,
    @field:Column(name = "ai_summary", columnDefinition = "TEXT")
    var aiSummary: String? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "summary_status", nullable = false, length = 16)
    var summaryStatus: CveSummaryStatus = CveSummaryStatus.PENDING,
    @field:Column(name = "claim_token", length = 36)
    var claimToken: String? = null,
    @field:Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,
    @field:Column(name = "next_attempt_at")
    var nextAttemptAt: LocalDateTime? = null,
    @field:Column(name = "published_at")
    val publishedAt: LocalDateTime? = null,
    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @field:UpdateTimestamp
    @field:Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,
)
