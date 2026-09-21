package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import java.time.LocalDateTime

data class UndeliveredCveEvent(
    val eventId: Long,
    val userId: String,
    val topicKey: String,
    val topicDisplayName: String,
    val title: String,
    val aiSummary: String?,
)

interface CveDeliveryRepository {
    fun claim(eventId: Long, userId: String): Boolean

    fun findUndelivered(
        deliveryMode: CveDeliveryMode,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent>

    fun dbNow(): LocalDateTime
}
