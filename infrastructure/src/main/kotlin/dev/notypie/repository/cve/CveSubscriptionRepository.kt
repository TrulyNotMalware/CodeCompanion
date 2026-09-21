package dev.notypie.repository.cve

interface CveSubscriptionRepository {
    fun subscribe(userId: String, topicIds: List<Long>): Int

    fun unsubscribe(userId: String, topicIds: List<Long>): Int

    fun findSubscribedTopics(userId: String): List<CveTopic>
}
