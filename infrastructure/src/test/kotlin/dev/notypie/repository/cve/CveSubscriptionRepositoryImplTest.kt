package dev.notypie.repository.cve

import dev.notypie.schema.createCveSubscriptionSchema
import dev.notypie.schema.createCveTopicSchema
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class CveSubscriptionRepositoryImplTest :
    BehaviorSpec({
        fun repositoryWith(jpaSub: JpaCveSubscriptionRepository, jpaTopic: JpaCveTopicRepository = mockk()) =
            CveSubscriptionRepositoryImpl(
                jpaCveSubscriptionRepository = jpaSub,
                jpaCveTopicRepository = jpaTopic,
            )

        given("subscribe where one of the requested topics is already subscribed") {
            val jpaSub = mockk<JpaCveSubscriptionRepository>()
            val repository = repositoryWith(jpaSub = jpaSub)
            every { jpaSub.insertIgnore(userId = "U1", topicId = 1L) } returns 0
            every { jpaSub.insertIgnore(userId = "U1", topicId = 2L) } returns 1

            `when`("subscribing to topics 1 and 2") {
                val inserted = repository.subscribe(userId = "U1", topicIds = listOf(1L, 2L, 2L))

                then("both pairs go through INSERT IGNORE once and only real inserts count") {
                    inserted shouldBe 1
                    verify(exactly = 1) { jpaSub.insertIgnore(userId = "U1", topicId = 1L) }
                    verify(exactly = 1) { jpaSub.insertIgnore(userId = "U1", topicId = 2L) }
                }
            }
        }

        given("subscribe with an empty topic list") {
            val jpaSub = mockk<JpaCveSubscriptionRepository>()
            val repository = repositoryWith(jpaSub = jpaSub)

            `when`("subscribing") {
                val inserted = repository.subscribe(userId = "U1", topicIds = emptyList())

                then("no insert is attempted") {
                    inserted shouldBe 0
                    verify(exactly = 0) { jpaSub.insertIgnore(userId = any(), topicId = any()) }
                }
            }
        }

        given("unsubscribe from two topics") {
            val jpaSub = mockk<JpaCveSubscriptionRepository>()
            val repository = repositoryWith(jpaSub = jpaSub)
            every { jpaSub.deleteByUserIdAndTopicIdIn(userId = "U1", topicIds = listOf(3L, 4L)) } returns 2L

            `when`("unsubscribing") {
                val removed = repository.unsubscribe(userId = "U1", topicIds = listOf(3L, 4L))

                then("the deleted row count is returned") {
                    removed shouldBe 2
                }
            }
        }

        given("unsubscribe with an empty topic list") {
            val jpaSub = mockk<JpaCveSubscriptionRepository>()
            val repository = repositoryWith(jpaSub = jpaSub)

            `when`("unsubscribing") {
                val removed = repository.unsubscribe(userId = "U1", topicIds = emptyList())

                then("no delete is issued") {
                    removed shouldBe 0
                    verify(exactly = 0) { jpaSub.deleteByUserIdAndTopicIdIn(userId = any(), topicIds = any()) }
                }
            }
        }

        given("findSubscribedTopics for a user with subscriptions") {
            val jpaSub = mockk<JpaCveSubscriptionRepository>()
            val jpaTopic = mockk<JpaCveTopicRepository>()
            val repository = repositoryWith(jpaSub = jpaSub, jpaTopic = jpaTopic)
            every { jpaSub.findByUserId(userId = "U1") } returns
                listOf(
                    createCveSubscriptionSchema(userId = "U1", topicId = 2L),
                    createCveSubscriptionSchema(userId = "U1", topicId = 1L),
                )
            every { jpaTopic.findAllById(any()) } returns
                listOf(
                    createCveTopicSchema(id = 2L, topicKey = "spring", displayName = "Spring"),
                    createCveTopicSchema(id = 1L, topicKey = "cve-java", displayName = "Java CVE"),
                )

            `when`("queried") {
                val topics = repository.findSubscribedTopics(userId = "U1")

                then("subscribed topics are mapped and ordered by topicKey") {
                    topics.map { it.topicKey } shouldContainExactly listOf("cve-java", "spring")
                }
            }
        }

        given("findSubscribedTopics for a user with no subscriptions") {
            val jpaSub = mockk<JpaCveSubscriptionRepository>()
            val jpaTopic = mockk<JpaCveTopicRepository>()
            val repository = repositoryWith(jpaSub = jpaSub, jpaTopic = jpaTopic)
            every { jpaSub.findByUserId(userId = "U1") } returns emptyList()

            `when`("queried") {
                val topics = repository.findSubscribedTopics(userId = "U1")

                then("the topic table is never touched") {
                    topics shouldBe emptyList()
                    verify(exactly = 0) { jpaTopic.findAllById(any()) }
                }
            }
        }
    })
