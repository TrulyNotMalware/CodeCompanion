package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveTopicSchema
import org.springframework.transaction.annotation.Transactional

open class CveSubscriptionRepositoryImpl(
    private val jpaCveSubscriptionRepository: JpaCveSubscriptionRepository,
    private val jpaCveTopicRepository: JpaCveTopicRepository,
) : CveSubscriptionRepository {
    @Transactional
    override fun subscribe(userId: String, topicIds: List<Long>): Int {
        if (topicIds.isEmpty()) return 0
        return topicIds.distinct().sumOf { topicId ->
            jpaCveSubscriptionRepository.insertIgnore(userId = userId, topicId = topicId)
        }
    }

    @Transactional
    override fun unsubscribe(userId: String, topicIds: List<Long>): Int {
        if (topicIds.isEmpty()) return 0
        return jpaCveSubscriptionRepository
            .deleteByUserIdAndTopicIdIn(userId = userId, topicIds = topicIds.distinct())
            .toInt()
    }

    override fun findSubscribedTopics(userId: String): List<CveTopic> {
        val topicIds = jpaCveSubscriptionRepository.findByUserId(userId = userId).map { it.topicId }
        if (topicIds.isEmpty()) return emptyList()
        return jpaCveTopicRepository
            .findAllById(topicIds)
            .map { toRecord(schema = it) }
            .sortedBy { it.topicKey }
    }

    private fun toRecord(schema: CveTopicSchema): CveTopic =
        CveTopic(
            id = schema.id,
            topicKey = schema.topicKey,
            displayName = schema.displayName,
            category = schema.category,
            sourceType = schema.sourceType,
            sourceConfig = schema.sourceConfig,
            deliveryMode = schema.deliveryMode,
            active = schema.active,
        )
}
