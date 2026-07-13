package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.schema.createCveEventSchema
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
    ) : BehaviorSpec({
            val now = LocalDateTime.of(2026, 7, 13, 12, 0)

            fun freshRow(externalId: String): Long =
                repository.saveAndFlush(createCveEventSchema(externalId = externalId)).id

            given("two workers racing for the same PENDING row") {
                val id = freshRow(externalId = "race-1")

                `when`("both attempt the claim") {
                    val first = repository.claimForSummary(id = id, token = "token-a", now = now)
                    val second = repository.claimForSummary(id = id, token = "token-b", now = now)

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
                repository.claimForSummary(id = id, token = "owner", now = now)

                `when`("markDone runs with the wrong then the owning token") {
                    val wrong = repository.markDone(id = id, token = "intruder", summary = "S")
                    val right = repository.markDone(id = id, token = "owner", summary = "S")

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
                repository.claimForSummary(id = id, token = "owner", now = now)

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
                repository.claimForSummary(id = id, token = "owner", now = now)

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
                        claimable.map { it.id } shouldContainExactly listOf(pending, retryElapsed)
                    }
                }
            }

            given("one stale and one live SUMMARIZING row") {
                val stale = freshRow(externalId = "stuck-old")
                repository.claimForSummary(id = stale, token = "crashed", now = now.minusMinutes(30))
                val live = freshRow(externalId = "stuck-live")
                repository.claimForSummary(id = live, token = "working", now = now.minusMinutes(1))

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
        })
