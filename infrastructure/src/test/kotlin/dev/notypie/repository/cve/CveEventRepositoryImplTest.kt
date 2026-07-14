package dev.notypie.repository.cve

import dev.notypie.schema.createCveEventSchema
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

class CveEventRepositoryImplTest :
    BehaviorSpec({
        val now = LocalDateTime.of(2026, 7, 13, 9, 0)

        given("insertIgnore for a new event") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            val publishedAt = now.minusHours(1)
            every {
                jpa.insertIgnore(
                    topicId = 1L,
                    externalId = "R1",
                    title = "Title",
                    rawContent = "Body",
                    publishedAt = publishedAt,
                )
            } returns 1

            `when`("inserting") {
                val inserted =
                    repository.insertIgnore(
                        topicId = 1L,
                        externalId = "R1",
                        title = "Title",
                        rawContent = "Body",
                        publishedAt = publishedAt,
                    )

                then("it delegates and returns the affected-row count") {
                    inserted shouldBe 1
                    verify(exactly = 1) {
                        jpa.insertIgnore(
                            topicId = 1L,
                            externalId = "R1",
                            title = "Title",
                            rawContent = "Body",
                            publishedAt = publishedAt,
                        )
                    }
                }
            }
        }

        given("insertIgnore with an over-long title and rawContent") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            val titleSlot = slot<String>()
            val rawContentSlot = slot<String>()
            every {
                jpa.insertIgnore(
                    topicId = 1L,
                    externalId = "R2",
                    title = capture(titleSlot),
                    rawContent = capture(rawContentSlot),
                    publishedAt = null,
                )
            } returns 1

            `when`("inserting") {
                repository.insertIgnore(
                    topicId = 1L,
                    externalId = "R2",
                    title = "t".repeat(600),
                    rawContent = "b".repeat(70_000),
                    publishedAt = null,
                )

                then("title is truncated to 512 and rawContent to 60000 before delegation") {
                    titleSlot.captured.length shouldBe CveEventRepositoryImpl.TITLE_MAX_LENGTH
                    rawContentSlot.captured.length shouldBe CveEventRepositoryImpl.RAW_CONTENT_MAX_LENGTH
                }
            }
        }

        given("findClaimable") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            every {
                jpa.findClaimable(now = now, maxRetries = 5, pageable = PageRequest.of(0, 10))
            } returns
                listOf(
                    createCveEventSchema(id = 1L, externalId = "CVE-1"),
                    createCveEventSchema(id = 2L, externalId = "CVE-2"),
                )

            `when`("querying with limit 10") {
                val events = repository.findClaimable(now = now, maxRetries = 5, limit = 10)

                then("it pages from zero with the given limit and maps rows to records") {
                    events.map { it.externalId } shouldContainExactly listOf("CVE-1", "CVE-2")
                    verify(exactly = 1) {
                        jpa.findClaimable(now = now, maxRetries = 5, pageable = PageRequest.of(0, 10))
                    }
                }
            }
        }

        given("claimForSummary") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            every { jpa.claimForSummary(id = 7L, token = "tok", now = now) } returns 1

            `when`("claiming") {
                val claimed = repository.claimForSummary(id = 7L, token = "tok", now = now)

                then("it delegates and returns the affected-row count") {
                    claimed shouldBe 1
                    verify(exactly = 1) { jpa.claimForSummary(id = 7L, token = "tok", now = now) }
                }
            }
        }

        given("markDone") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            every { jpa.markDone(id = 7L, token = "tok", summary = "summary") } returns 1

            `when`("marking done") {
                val updated = repository.markDone(id = 7L, token = "tok", summary = "summary")

                then("it delegates with the summary and owning token") {
                    updated shouldBe 1
                    verify(exactly = 1) { jpa.markDone(id = 7L, token = "tok", summary = "summary") }
                }
            }
        }

        given("markFailed") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            val nextAttemptAt = now.plusMinutes(20)
            every { jpa.markFailed(id = 7L, token = "tok", nextAttemptAt = nextAttemptAt) } returns 1

            `when`("marking failed") {
                val updated = repository.markFailed(id = 7L, token = "tok", nextAttemptAt = nextAttemptAt)

                then("it delegates the next-attempt schedule and owning token") {
                    updated shouldBe 1
                    verify(exactly = 1) { jpa.markFailed(id = 7L, token = "tok", nextAttemptAt = nextAttemptAt) }
                }
            }
        }

        given("resetStuck") {
            val jpa = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpa)
            val olderThan = now.minusMinutes(15)
            every { jpa.resetStuck(olderThan = olderThan) } returns 3

            `when`("resetting stuck rows") {
                val reset = repository.resetStuck(olderThan = olderThan)

                then("it delegates and returns the reset count") {
                    reset shouldBe 3
                    verify(exactly = 1) { jpa.resetStuck(olderThan = olderThan) }
                }
            }
        }
        given("releaseClaim") {
            val jpaCveEventRepository = mockk<JpaCveEventRepository>()
            val repository = CveEventRepositoryImpl(jpaCveEventRepository = jpaCveEventRepository)
            val nextAttemptAt = LocalDateTime.of(2026, 7, 13, 12, 0)
            every {
                jpaCveEventRepository.releaseClaim(id = 1L, token = "tok", nextAttemptAt = nextAttemptAt)
            } returns 1

            `when`("delegated") {
                val released = repository.releaseClaim(id = 1L, token = "tok", nextAttemptAt = nextAttemptAt)

                then("the row count from the CAS comes back unchanged") {
                    released shouldBe 1
                }
            }
        }
    })
