package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.schema.createCveDeliverySchema
import dev.notypie.schema.createCveEventSchema
import dev.notypie.schema.createCveSubscriptionSchema
import dev.notypie.schema.createCveTopicSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime
import javax.sql.DataSource

/**
 * Exercises the [JpaCveDeliveryRepository.findUndelivered] entity-join JPQL against a real database.
 * Setup writes go through the self-transactional Jpa repositories because kotest container scopes run
 * outside the test transaction; rows therefore persist across blocks, so every topicKey/externalId/
 * userId is unique and each assertion filters the result to its own block's subscribers. The horizon
 * ([since]) and visibility cutoff ([doneBefore]) stay wide by default so every fixture is visible; the
 * two boundary blocks tighten them (aging created_at via raw SQL, since @CreationTimestamp ignores
 * supplied values on persist).
 */
@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaCveDeliveryRepositoryTest
    @Autowired
    constructor(
        private val deliveryRepository: JpaCveDeliveryRepository,
        private val topicRepository: JpaCveTopicRepository,
        private val eventRepository: JpaCveEventRepository,
        private val subscriptionRepository: JpaCveSubscriptionRepository,
        dataSource: DataSource,
    ) : BehaviorSpec({
            val page = PageRequest.of(0, 50)
            val jdbcTemplate = JdbcTemplate(dataSource)
            val since = LocalDateTime.now().minusDays(7)
            val doneBefore = LocalDateTime.now().plusDays(1)

            // @DataJpaTest specs share one cached context (and H2 db), and these rows are committed
            // (not rolled back), so the claimable cve_event rows below would otherwise leak into
            // JpaCveEventRepositoryTest's findClaimable assertion — clear them once this spec is done.
            afterSpec {
                deliveryRepository.deleteAll()
                subscriptionRepository.deleteAll()
                eventRepository.deleteAll()
                topicRepository.deleteAll()
            }

            fun saveTopic(
                topicKey: String,
                deliveryMode: CveDeliveryMode = CveDeliveryMode.IMMEDIATE,
                active: Boolean = true,
            ): Long =
                topicRepository
                    .saveAndFlush(
                        createCveTopicSchema(topicKey = topicKey, deliveryMode = deliveryMode, active = active),
                    ).id

            fun saveEvent(
                topicId: Long,
                externalId: String,
                summaryStatus: CveSummaryStatus = CveSummaryStatus.DONE,
            ): Long =
                eventRepository
                    .saveAndFlush(
                        createCveEventSchema(
                            topicId = topicId,
                            externalId = externalId,
                            summaryStatus = summaryStatus,
                            aiSummary = "summary",
                        ),
                    ).id

            fun subscribe(userId: String, topicId: Long) {
                subscriptionRepository.saveAndFlush(createCveSubscriptionSchema(userId = userId, topicId = topicId))
            }

            fun undelivered(
                mode: CveDeliveryMode,
                scanSince: LocalDateTime = since,
                cutoff: LocalDateTime = doneBefore,
                pageable: Pageable = page,
            ): List<UndeliveredCveEvent> =
                deliveryRepository.findUndelivered(
                    deliveryMode = mode,
                    since = scanSince,
                    doneBefore = cutoff,
                    pageable = pageable,
                )

            given("one immediate topic carrying an event in every summary status") {
                val topicId = saveTopic(topicKey = "done-filter")
                val doneId = saveEvent(topicId = topicId, externalId = "done-filter-done")
                saveEvent(
                    topicId = topicId,
                    externalId = "done-filter-pending",
                    summaryStatus = CveSummaryStatus.PENDING,
                )
                saveEvent(
                    topicId = topicId,
                    externalId = "done-filter-summarizing",
                    summaryStatus = CveSummaryStatus.SUMMARIZING,
                )
                saveEvent(topicId = topicId, externalId = "done-filter-failed", summaryStatus = CveSummaryStatus.FAILED)
                subscribe(userId = "U_DONE_FILTER", topicId = topicId)

                `when`("findUndelivered runs for IMMEDIATE") {
                    val result = undelivered(mode = CveDeliveryMode.IMMEDIATE).filter { it.userId == "U_DONE_FILTER" }

                    then("only the DONE event surfaces") {
                        result.map { it.eventId } shouldContainExactly listOf(doneId)
                    }
                }
            }

            given("a DIGEST topic with a DONE event and a subscriber") {
                val topicId = saveTopic(topicKey = "mode-filter", deliveryMode = CveDeliveryMode.DIGEST)
                val eventId = saveEvent(topicId = topicId, externalId = "mode-filter-done")
                subscribe(userId = "U_MODE_FILTER", topicId = topicId)

                `when`("findUndelivered runs for each mode") {
                    val immediate =
                        undelivered(
                            mode = CveDeliveryMode.IMMEDIATE,
                        ).filter { it.userId == "U_MODE_FILTER" }
                    val digest = undelivered(mode = CveDeliveryMode.DIGEST).filter { it.userId == "U_MODE_FILTER" }

                    then("the pair surfaces only under its topic's delivery mode") {
                        immediate.shouldBeEmpty()
                        digest.map { it.eventId } shouldContainExactly listOf(eventId)
                    }
                }
            }

            given("an inactive topic with a DONE event and a subscriber") {
                val topicId = saveTopic(topicKey = "inactive-topic", active = false)
                saveEvent(topicId = topicId, externalId = "inactive-topic-done")
                subscribe(userId = "U_INACTIVE", topicId = topicId)

                `when`("findUndelivered runs for IMMEDIATE") {
                    val result = undelivered(mode = CveDeliveryMode.IMMEDIATE).filter { it.userId == "U_INACTIVE" }

                    then("the inactive topic contributes nothing") {
                        result.shouldBeEmpty()
                    }
                }
            }

            given("a DONE event already recorded in the delivery ledger") {
                val topicId = saveTopic(topicKey = "already-delivered")
                val eventId = saveEvent(topicId = topicId, externalId = "already-delivered-done")
                subscribe(userId = "U_DELIVERED", topicId = topicId)
                deliveryRepository.saveAndFlush(createCveDeliverySchema(eventId = eventId, userId = "U_DELIVERED"))

                `when`("findUndelivered runs for IMMEDIATE") {
                    val result = undelivered(mode = CveDeliveryMode.IMMEDIATE).filter { it.userId == "U_DELIVERED" }

                    then("the already-delivered pair is excluded") {
                        result.shouldBeEmpty()
                    }
                }
            }

            given("one DONE event on a topic with two subscribers") {
                val topicId = saveTopic(topicKey = "fan-out")
                val eventId = saveEvent(topicId = topicId, externalId = "fan-out-done")
                subscribe(userId = "U_FANOUT_A", topicId = topicId)
                subscribe(userId = "U_FANOUT_B", topicId = topicId)

                `when`("findUndelivered runs for IMMEDIATE") {
                    val result =
                        undelivered(
                            mode = CveDeliveryMode.IMMEDIATE,
                        ).filter { it.userId.startsWith("U_FANOUT_") }

                    then("the event fans out to one pair per subscriber, ordered by user id") {
                        result.map { it.userId } shouldContainExactly listOf("U_FANOUT_A", "U_FANOUT_B")
                        result.map { it.eventId } shouldContainExactly listOf(eventId, eventId)
                    }
                }
            }

            given("a DONE event created before the delivery horizon") {
                val topicId = saveTopic(topicKey = "horizon")
                val oldId = saveEvent(topicId = topicId, externalId = "horizon-old")
                subscribe(userId = "U_HORIZON", topicId = topicId)
                jdbcTemplate.update(
                    "UPDATE cve_event SET created_at = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(30)),
                    oldId,
                )

                `when`("findUndelivered runs with the default 7-day horizon") {
                    val result = undelivered(mode = CveDeliveryMode.IMMEDIATE).filter { it.userId == "U_HORIZON" }

                    then("the event older than the horizon is excluded") {
                        result.shouldBeEmpty()
                    }
                }
            }

            given("a DONE event summarized at or after the visibility cutoff") {
                val topicId = saveTopic(topicKey = "cutoff")
                saveEvent(topicId = topicId, externalId = "cutoff-late")
                subscribe(userId = "U_CUTOFF", topicId = topicId)

                `when`("findUndelivered runs with a cutoff before the event was summarized") {
                    val result =
                        undelivered(mode = CveDeliveryMode.IMMEDIATE, cutoff = LocalDateTime.now().minusDays(1))
                            .filter { it.userId == "U_CUTOFF" }

                    then("the event summarized after the cutoff is excluded") {
                        result.shouldBeEmpty()
                    }
                }
            }

            given("more undelivered pairs than the requested limit") {
                val topicId = saveTopic(topicKey = "limit")
                saveEvent(topicId = topicId, externalId = "limit-1")
                saveEvent(topicId = topicId, externalId = "limit-2")
                saveEvent(topicId = topicId, externalId = "limit-3")
                subscribe(userId = "U_LIMIT", topicId = topicId)

                `when`("findUndelivered runs with a limit of two") {
                    val result = undelivered(mode = CveDeliveryMode.IMMEDIATE, pageable = PageRequest.of(0, 2))

                    then("no more than the limit is returned") {
                        result.size shouldBe 2
                    }
                }
            }

            given("the database clock read that anchors the delivery horizon") {
                `when`("dbNow runs") {
                    val dbNow = deliveryRepository.dbNow()

                    then("the native scalar maps to a LocalDateTime near the JVM clock (in-process H2)") {
                        Duration.between(dbNow, LocalDateTime.now()).abs().seconds shouldBeLessThan 60L
                    }
                }
            }
        })
