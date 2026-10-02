package dev.notypie.application.service.cve.ai

import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.schema.createCveEvent
import dev.notypie.schema.createCveTopic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// The prod JVM runs in Asia/Seoul; pinning the clock there keeps the wall-clock values the worker binds explicit.
private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-13T03:00:00Z"), ZoneId.of("Asia/Seoul"))
private val NOW: LocalDateTime = LocalDateTime.of(2026, 7, 13, 12, 0)

class CveSummaryWorkerTest :
    BehaviorSpec({
        val backoffMinutes = 10L
        val stuckMinutes = 15L

        fun workerWith(
            eventRepository: CveEventRepository,
            topicRepository: CveTopicRepository = mockk(),
            summarizer: AiSummarizer = mockk(),
        ): CveSummaryWorker =
            CveSummaryWorker(
                cveEventRepository = eventRepository,
                cveTopicRepository = topicRepository,
                aiSummarizer = summarizer,
                batchSize = 10,
                maxRetries = 5,
                backoffMinutes = backoffMinutes,
                stuckMinutes = stuckMinutes,
                clock = FIXED_CLOCK,
            )

        given("a tick against the injected clock") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L), createCveEvent(id = 2L, topicId = 10L))
            every { eventRepository.claimForSummary(id = any(), token = any(), now = any(), maxRetries = 5) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = match { it.eventId == 1L }) } returns "SUMMARY"
            every { summarizer.summarize(request = match { it.eventId == 2L }) } throws
                AiSummarizerBusyException(message = "busy")
            every { eventRepository.markDone(id = 1L, token = any(), summary = "SUMMARY", now = any()) } returns 1
            every { eventRepository.releaseClaim(id = 2L, token = any(), nextAttemptAt = any(), now = any()) } returns 1
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("every cutoff and every updated_at stamp comes from that clock, never the DB or JVM default") {
                    verify(exactly = 1) {
                        eventRepository.resetStuck(
                            olderThan = NOW.minusMinutes(stuckMinutes),
                            nextAttemptAt = NOW.plusMinutes(backoffMinutes),
                            now = NOW,
                        )
                    }
                    verify(exactly = 1) { eventRepository.findClaimable(now = NOW, maxRetries = 5, limit = 10) }
                    verify(exactly = 2) {
                        eventRepository.claimForSummary(id = any(), token = any(), now = NOW, maxRetries = 5)
                    }
                    verify(exactly = 1) {
                        eventRepository.markDone(id = 1L, token = any(), summary = "SUMMARY", now = NOW)
                    }
                    verify(exactly = 1) {
                        eventRepository.releaseClaim(
                            id = 2L,
                            token = any(),
                            nextAttemptAt = NOW.plusMinutes(2),
                            now = NOW,
                        )
                    }
                }
            }
        }

        given("a claimable event that summarizes cleanly") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            val claimToken = slot<String>()
            val doneToken = slot<String>()
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L))
            every {
                eventRepository.claimForSummary(id = 1L, token = capture(claimToken), now = any(), maxRetries = 5)
            } returns 1
            every { topicRepository.findById(id = 10L) } returns
                createCveTopic(id = 10L, displayName = "Java CVE")
            every { summarizer.summarize(request = any()) } returns "SUMMARY"
            every {
                eventRepository.markDone(id = 1L, token = capture(doneToken), summary = "SUMMARY", now = any())
            } returns 1
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("stuck rows are reset once and the batch is claimed with the configured knobs") {
                    verify(exactly = 1) {
                        eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any())
                    }
                    verify(exactly = 1) { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) }
                }

                then("the summarizer output is stored under the same claim token, no failure recorded") {
                    verify(exactly = 1) {
                        eventRepository.markDone(id = 1L, token = any(), summary = "SUMMARY", now = any())
                    }
                    claimToken.captured shouldBe doneToken.captured
                    verify(exactly = 0) {
                        eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any(), now = any())
                    }
                }
            }
        }

        given("an event already claimed by another instance") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val summarizer = mockk<AiSummarizer>()
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L))
            every { eventRepository.claimForSummary(id = 1L, token = any(), now = any(), maxRetries = 5) } returns 0
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the summarizer is never invoked and nothing is marked done or failed") {
                    verify(exactly = 0) { summarizer.summarize(request = any()) }
                    verify(exactly = 0) { topicRepository.findById(id = any()) }
                    verify(exactly = 0) {
                        eventRepository.markDone(id = any(), token = any(), summary = any(), now = any())
                    }
                    verify(exactly = 0) {
                        eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any(), now = any())
                    }
                }
            }
        }

        given("an event whose summarizer throws, followed by a healthy event") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            val failToken = slot<String>()
            val nextAttemptAt = slot<LocalDateTime>()
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(
                    createCveEvent(id = 1L, topicId = 10L, retryCount = 2),
                    createCveEvent(id = 2L, topicId = 10L, retryCount = 0),
                )
            every {
                eventRepository.claimForSummary(id = 1L, token = capture(failToken), now = any(), maxRetries = 5)
            } returns 1
            every { eventRepository.claimForSummary(id = 2L, token = any(), now = any(), maxRetries = 5) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = match { it.eventId == 1L }) } throws
                AiSummarizationException(message = "boom")
            every { summarizer.summarize(request = match { it.eventId == 2L }) } returns "OK"
            every {
                eventRepository.markFailed(
                    id = 1L,
                    token = capture(failToken),
                    nextAttemptAt = capture(nextAttemptAt),
                    now = NOW,
                )
            } returns 1
            every { eventRepository.markDone(id = 2L, token = any(), summary = "OK", now = any()) } returns 1
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the failing event is marked failed with backoff = backoffMinutes * (retryCount + 1)") {
                    verify(exactly = 1) {
                        eventRepository.markFailed(id = 1L, token = any(), nextAttemptAt = any(), now = NOW)
                    }
                    nextAttemptAt.captured shouldBe NOW.plusMinutes(backoffMinutes * 3)
                }

                then("the loop continues and the healthy event is summarized and marked done") {
                    verify(
                        exactly = 1,
                    ) { eventRepository.markDone(id = 2L, token = any(), summary = "OK", now = any()) }
                }
            }
        }

        given("a tick with no claimable events") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 4
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns emptyList()
            val worker = workerWith(eventRepository)

            `when`("the tick runs") {
                worker.tick()

                then("stuck recovery still runs exactly once") {
                    verify(exactly = 1) {
                        eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any())
                    }
                }
            }
        }
        given("an event whose summarizer signals busy backpressure") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L, retryCount = 3))
            every { eventRepository.claimForSummary(id = 1L, token = any(), now = any(), maxRetries = 5) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = any()) } throws
                AiSummarizerBusyException(message = "Sidecar busy (code=busy) for event=1")
            every { eventRepository.releaseClaim(id = 1L, token = any(), nextAttemptAt = any(), now = any()) } returns 1
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the claim is released without consuming the retry budget") {
                    verify(exactly = 1) {
                        eventRepository.releaseClaim(id = 1L, token = any(), nextAttemptAt = any(), now = any())
                    }
                    verify(exactly = 0) {
                        eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any(), now = any())
                    }
                    verify(exactly = 0) {
                        eventRepository.markDone(id = any(), token = any(), summary = any(), now = any())
                    }
                }
            }
        }

        given("a summarized event whose claim was lost before markDone") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            every { eventRepository.resetStuck(olderThan = any(), nextAttemptAt = any(), now = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L))
            every { eventRepository.claimForSummary(id = 1L, token = any(), now = any(), maxRetries = 5) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = any()) } returns "SUMMARY"
            every { eventRepository.markDone(id = 1L, token = any(), summary = "SUMMARY", now = any()) } returns 0
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the lost claim is not treated as a failure — the retry budget stays intact") {
                    verify(exactly = 0) {
                        eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any(), now = any())
                    }
                    verify(exactly = 0) {
                        eventRepository.releaseClaim(id = any(), token = any(), nextAttemptAt = any(), now = any())
                    }
                }
            }
        }
    })
