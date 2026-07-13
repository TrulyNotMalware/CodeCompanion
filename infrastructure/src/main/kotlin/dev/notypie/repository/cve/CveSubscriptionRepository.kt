package dev.notypie.repository.cve

interface CveSubscriptionRepository {
    /**
     * Subscribes [userId] to [topicIds], skipping any pair that already exists so a resubmission is
     * idempotent. Returns the number of rows actually inserted.
     */
    fun subscribe(userId: String, topicIds: List<Long>): Int

    /** Removes [userId]'s subscriptions to [topicIds]. Returns the number of rows deleted. */
    fun unsubscribe(userId: String, topicIds: List<Long>): Int

    /** The topics [userId] is currently subscribed to, ordered by topicKey. */
    fun findSubscribedTopics(userId: String): List<CveTopic>
}
