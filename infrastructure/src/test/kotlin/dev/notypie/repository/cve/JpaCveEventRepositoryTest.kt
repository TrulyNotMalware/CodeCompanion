package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.schema.createCveEventSchema
import dev.notypie.schema.createCveTopicSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

/**
 * Runs the claim-token CAS against a real database: the native WHERE guards below are what
 * multi-instance safety rests on, and mocked-JPA tests cannot exercise them. Setup writes go
 * through the repository (self-transactional) because kotest container scopes run outside the
 * test transaction; rows therefore persist across blocks, so every externalId is unique and the
 * findClaimable assertion accounts for rows left behind by earlier blocks.
 */
@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaCveEventRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaCveEventRepository,
        private val topicRepository: JpaCveTopicRepository,
    ) : BehaviorSpec({
            val now = LocalDateTime.of(2026, 7, 13, 12, 0)

            fun freshRow(externalId: String): Long =
                repository.saveAndFlush(createCveEventSchema(externalId = externalId)).id

            given("two workers racing for the same PENDING row") {
                val id = freshRow(externalId = "race-1")

                `when`("both attempt the claim") {
                    val first = repository.claimForSummary(id = id, token = "token-a", now = now, maxRetries = 5)
                    val second = repository.claimForSummary(id = id, token = "token-b", now = now, maxRetries = 5)

                    then("exactly one wins and the row carries the winner's token") {
                        first shouldBe 1
                        second shouldBe 0
                        val row = repository.findById(id).orElseThrow()
                        row.summaryStatus shouldBe CveSummaryStatus.SUMMARIZING
                        row.claimToken shouldBe "token-a"
                        row.updatedAt shouldBe now
                    }
                }
            }

            given("a claimed row and a foreign token") {
                val id = freshRow(externalId = "done-1")
                repository.claimForSummary(id = id, token = "owner", now = now, maxRetries = 5)

                `when`("markDone runs with the wrong then the owning token") {
                    val wrong = repository.markDone(id = id, token = "intruder", summary = "S", now = now)
                    val right = repository.markDone(id = id, token = "owner", summary = "S", now = now)

                    then("only the owner completes the row and the token is cleared") {
                        wrong shouldBe 0
                        right shouldBe 1
                        val row = repository.findById(id).orElseThrow()
                        row.summaryStatus shouldBe CveSummaryStatus.DONE
                        row.aiSummary shouldBe "S"
                        row.claimToken.shouldBeNull()
                    }
                }
            }

            given("a claimed row that fails") {
                val id = freshRow(externalId = "fail-1")
                repository.claimForSummary(id = id, token = "owner", now = now, maxRetries = 5)

                `when`("markFailed runs with the owning token") {
                    val marked = repository.markFailed(id = id, token = "owner", nextAttemptAt = now.plusMinutes(10))

                    then("the retry budget is consumed and the next attempt is scheduled") {
                        marked shouldBe 1
                        val row = repository.findById(id).orElseThrow()
                        row.summaryStatus shouldBe CveSummaryStatus.FAILED
                        row.retryCount shouldBe 1
                        row.nextAttemptAt shouldBe now.plusMinutes(10)
                        row.claimToken.shouldBeNull()
                    }
                }
            }

            given("a claimed row released for backpressure") {
                val id = freshRow(externalId = "busy-1")
                repository.claimForSummary(id = id, token = "owner", now = now, maxRetries = 5)

                `when`("releaseClaim runs with the owning token") {
                    val released = repository.releaseClaim(id = id, token = "owner", nextAttemptAt = now.plusMinutes(2))

                    then("the row returns to PENDING with its retry budget intact") {
                        released shouldBe 1
                        val row = repository.findById(id).orElseThrow()
                        row.summaryStatus shouldBe CveSummaryStatus.PENDING
                        row.retryCount shouldBe 0
                        row.nextAttemptAt shouldBe now.plusMinutes(2)
                        row.claimToken.shouldBeNull()
                    }
                }
            }

            given("rows in every lifecycle state") {
                val pending = freshRow(externalId = "claimable-pending")
                val retryElapsed =
                    repository
                        .saveAndFlush(
                            createCveEventSchema(
                                externalId = "claimable-failed",
                                summaryStatus = CveSummaryStatus.FAILED,
                                retryCount = 2,
                                nextAttemptAt = now.minusMinutes(1),
                            ),
                        ).id
                repository.saveAndFlush(
                    createCveEventSchema(
                        externalId = "dead-max-retries",
                        summaryStatus = CveSummaryStatus.FAILED,
                        retryCount = 5,
                        nextAttemptAt = now.minusMinutes(1),
                    ),
                )
                repository.saveAndFlush(
                    createCveEventSchema(
                        externalId = "backoff-future",
                        summaryStatus = CveSummaryStatus.FAILED,
                        retryCount = 1,
                        nextAttemptAt = now.plusMinutes(30),
                    ),
                )
                repository.saveAndFlush(
                    createCveEventSchema(externalId = "already-done", summaryStatus = CveSummaryStatus.DONE),
                )
                repository.saveAndFlush(
                    createCveEventSchema(externalId = "in-flight", summaryStatus = CveSummaryStatus.SUMMARIZING),
                )

                `when`("findClaimable runs") {
                    val claimable =
                        repository.findClaimable(now = now, maxRetries = 5, pageable = PageRequest.of(0, 10))

                    then("only PENDING and retry-elapsed FAILED rows below the budget come back, id-ordered") {
                        // Other committing specs share this H2 db, so scope the assertion to this
                        // block's own fixture rows by their externalIds.
                        val blockExternalIds =
                            setOf(
                                "claimable-pending",
                                "claimable-failed",
                                "dead-max-retries",
                                "backoff-future",
                                "already-done",
                                "in-flight",
                            )
                        claimable
                            .filter { it.externalId in blockExternalIds }
                            .map { it.id } shouldContainExactly listOf(pending, retryElapsed)
                    }
                }
            }

            given("one stale and one live SUMMARIZING row") {
                val stale = freshRow(externalId = "stuck-old")
                repository.claimForSummary(id = stale, token = "crashed", now = now.minusMinutes(30), maxRetries = 5)
                val live = freshRow(externalId = "stuck-live")
                repository.claimForSummary(id = live, token = "working", now = now.minusMinutes(1), maxRetries = 5)

                `when`("resetStuck runs with a 15-minute threshold") {
                    val reset = repository.resetStuck(olderThan = now.minusMinutes(15))

                    then("only the stale row returns to PENDING; the live claim keeps its token") {
                        reset shouldBe 1
                        repository.findById(stale).orElseThrow().summaryStatus shouldBe CveSummaryStatus.PENDING
                        val liveRow = repository.findById(live).orElseThrow()
                        liveRow.summaryStatus shouldBe CveSummaryStatus.SUMMARIZING
                        liveRow.claimToken.shouldNotBeNull()
                    }
                }
            }

            given("the ops status counters over a mixed population") {
                // The counters are global and earlier blocks leave committed rows behind, so every
                // assertion compares against a baseline captured before this block's own writes.
                val basePending = repository.countByStatus(status = CveSummaryStatus.PENDING)
                val baseSummarizing = repository.countByStatus(status = CveSummaryStatus.SUMMARIZING)
                val baseRetryable = repository.countFailedRetryable(maxRetries = 5)
                val baseDeadLetter = repository.countDeadLetter(maxRetries = 5)
                repository.saveAndFlush(createCveEventSchema(externalId = "counter-pending"))
                repository.saveAndFlush(
                    createCveEventSchema(externalId = "counter-inflight", summaryStatus = CveSummaryStatus.SUMMARIZING),
                )
                repository.saveAndFlush(
                    createCveEventSchema(
                        externalId = "counter-retryable",
                        summaryStatus = CveSummaryStatus.FAILED,
                        retryCount = 1,
                    ),
                )
                repository.saveAndFlush(
                    createCveEventSchema(
                        externalId = "counter-dead",
                        summaryStatus = CveSummaryStatus.FAILED,
                        retryCount = 5,
                    ),
                )

                `when`("the counters run at a retry ceiling of 5") {
                    then("each status delta is one, and FAILED splits at the ceiling") {
                        repository.countByStatus(status = CveSummaryStatus.PENDING) shouldBe basePending + 1
                        repository.countByStatus(status = CveSummaryStatus.SUMMARIZING) shouldBe baseSummarizing + 1
                        repository.countFailedRetryable(maxRetries = 5) shouldBe baseRetryable + 1
                        repository.countDeadLetter(maxRetries = 5) shouldBe baseDeadLetter + 1
                    }
                }
            }

            given("events spread over two topics and a third topic with none") {
                repository.saveAndFlush(createCveEventSchema(topicId = 9001L, externalId = "topic-count-a1"))
                repository.saveAndFlush(createCveEventSchema(topicId = 9001L, externalId = "topic-count-a2"))
                repository.saveAndFlush(createCveEventSchema(topicId = 9002L, externalId = "topic-count-b1"))

                `when`("countEventsByTopic runs over all three ids") {
                    val counts = repository.countEventsByTopic(topicIds = listOf(9001L, 9002L, 9003L))

                    then("counts group per topic and the empty topic is absent") {
                        counts.sortedBy { it.topicId } shouldContainExactly
                            listOf(
                                TopicEventCount(topicId = 9001L, count = 2L),
                                TopicEventCount(topicId = 9002L, count = 1L),
                            )
                    }
                }
            }

            given("a topic with summarized, unsummarized, and foreign-topic events") {
                val topicId =
                    topicRepository
                        .saveAndFlush(
                            createCveTopicSchema(topicKey = "latest-topic", displayName = "Latest Topic"),
                        ).id
                repository.saveAndFlush(
                    createCveEventSchema(
                        topicId = topicId,
                        externalId = "latest-old",
                        title = "Old advisory",
                        aiSummary = "Old summary",
                        summaryStatus = CveSummaryStatus.DONE,
                    ),
                )
                repository.saveAndFlush(
                    createCveEventSchema(
                        topicId = topicId,
                        externalId = "latest-new",
                        title = "New advisory",
                        aiSummary = "New summary",
                        summaryStatus = CveSummaryStatus.DONE,
                    ),
                )
                repository.saveAndFlush(
                    createCveEventSchema(topicId = topicId, externalId = "latest-pending", title = "Unsummarized"),
                )
                repository.saveAndFlush(
                    createCveEventSchema(
                        topicId = 9099L,
                        externalId = "latest-foreign",
                        summaryStatus = CveSummaryStatus.DONE,
                    ),
                )

                `when`("findRecentDoneEvents runs scoped to the topic") {
                    val recent =
                        repository.findRecentDoneEvents(
                            topicIds = listOf(topicId),
                            pageable = PageRequest.of(0, 5),
                        )

                    then("only DONE events of that topic come back, newest first, joined to the display name") {
                        recent.map { it.title } shouldContainExactly listOf("New advisory", "Old advisory")
                        recent.first().topicDisplayName shouldBe "Latest Topic"
                        recent.first().aiSummary shouldBe "New summary"
                    }
                }

                `when`("findRecentDoneEvents runs with a limit of one") {
                    val recent =
                        repository.findRecentDoneEvents(
                            topicIds = listOf(topicId),
                            pageable = PageRequest.of(0, 1),
                        )

                    then("only the newest event returns") {
                        recent.map { it.title } shouldContainExactly listOf("New advisory")
                    }
                }
            }

            given("dead-letter, retryable, and completed rows at a retry ceiling of 50") {
                // Ceiling 50 keeps this block exact: rows leaked by other blocks stay below it.
                val dead1 =
                    repository
                        .saveAndFlush(
                            createCveEventSchema(
                                externalId = "revive-dead-1",
                                summaryStatus = CveSummaryStatus.FAILED,
                                retryCount = 50,
                                nextAttemptAt = now.plusMinutes(10),
                            ),
                        ).id
                val dead2 =
                    repository
                        .saveAndFlush(
                            createCveEventSchema(
                                externalId = "revive-dead-2",
                                summaryStatus = CveSummaryStatus.FAILED,
                                retryCount = 51,
                            ),
                        ).id
                val retryable =
                    repository
                        .saveAndFlush(
                            createCveEventSchema(
                                externalId = "revive-retryable",
                                summaryStatus = CveSummaryStatus.FAILED,
                                retryCount = 49,
                            ),
                        ).id

                `when`("one dead-letter is revived by id, then the rest in bulk") {
                    val retryableRefused = repository.resetDeadLetter(id = retryable, maxRetries = 50)
                    val singleRevived = repository.resetDeadLetter(id = dead1, maxRetries = 50)
                    val bulkRevived = repository.resetDeadLetters(maxRetries = 50)

                    then("guards admit only true dead-letters and revival resets the full budget") {
                        retryableRefused shouldBe 0
                        singleRevived shouldBe 1
                        bulkRevived shouldBe 1
                        val revived = repository.findById(dead1).orElseThrow()
                        revived.summaryStatus shouldBe CveSummaryStatus.PENDING
                        revived.retryCount shouldBe 0
                        revived.nextAttemptAt.shouldBeNull()
                        revived.claimToken.shouldBeNull()
                        repository.findById(dead2).orElseThrow().summaryStatus shouldBe CveSummaryStatus.PENDING
                        repository.findById(retryable).orElseThrow().summaryStatus shouldBe CveSummaryStatus.FAILED
                    }
                }
            }

            given("a FAILED row that exhausted its retry budget after a worker read it") {
                val dead =
                    repository
                        .saveAndFlush(
                            createCveEventSchema(
                                externalId = "stale-claim-dead",
                                summaryStatus = CveSummaryStatus.FAILED,
                                retryCount = 5,
                            ),
                        ).id

                `when`("the worker attempts the claim with its stale candidate") {
                    val claimed = repository.claimForSummary(id = dead, token = "stale", now = now, maxRetries = 5)

                    then("the CAS refuses the dead-letter row") {
                        claimed shouldBe 0
                        repository.findById(dead).orElseThrow().summaryStatus shouldBe CveSummaryStatus.FAILED
                    }
                }
            }
        })
