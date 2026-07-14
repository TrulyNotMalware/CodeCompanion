package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

open class CveDeliveryRepositoryImpl(
    private val jpaCveDeliveryRepository: JpaCveDeliveryRepository,
) : CveDeliveryRepository {
    @Transactional
    override fun claim(eventId: Long, userId: String): Boolean =
        jpaCveDeliveryRepository.claim(eventId = eventId, userId = userId) == 1

    override fun findUndelivered(
        deliveryMode: CveDeliveryMode,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent> =
        jpaCveDeliveryRepository.findUndelivered(
            deliveryMode = deliveryMode,
            since = since,
            doneBefore = doneBefore,
            pageable = PageRequest.of(0, limit),
        )
}
