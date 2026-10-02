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

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    override fun findUndeliveredByUser(
        deliveryMode: CveDeliveryMode,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent> =
        jpaCveDeliveryRepository.findUndeliveredByUser(
            deliveryMode = deliveryMode,
            since = since,
            doneBefore = doneBefore,
            pageable = PageRequest.of(0, limit),
        )

    @Transactional(readOnly = true)
    override fun findUndeliveredForUser(
        deliveryMode: CveDeliveryMode,
        userId: String,
        since: LocalDateTime,
        doneBefore: LocalDateTime,
        limit: Int,
    ): List<UndeliveredCveEvent> =
        jpaCveDeliveryRepository.findUndeliveredForUser(
            deliveryMode = deliveryMode,
            userId = userId,
            since = since,
            doneBefore = doneBefore,
            pageable = PageRequest.of(0, limit),
        )

    @Transactional(readOnly = true)
    override fun dbNow(): LocalDateTime = jpaCveDeliveryRepository.dbNow()
}
