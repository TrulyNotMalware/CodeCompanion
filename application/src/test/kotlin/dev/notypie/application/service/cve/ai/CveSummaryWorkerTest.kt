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
import java.time.LocalDateTime

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
            )

        given("a claimable event that summarizes cleanly") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            val claimToken = slot<String>()
            val doneToken = slot<String>()
            every { eventRepository.resetStuck(olderThan = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L))
            every { eventRepository.claimForSummary(id = 1L, token = capture(claimToken), now = any()) } returns 1
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
                    verify(exactly = 1) { eventRepository.resetStuck(olderThan = any()) }
                    verify(exactly = 1) { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) }
                }

                then("the summarizer output is stored under the same claim token, no failure recorded") {
                    verify(exactly = 1) {
                        eventRepository.markDone(id = 1L, token = any(), summary = "SUMMARY", now = any())
                    }
                    claimToken.captured shouldBe doneToken.captured
                    verify(exactly = 0) { eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any()) }
                }
            }
        }

        given("an event already claimed by another instance") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>(relaxed = true)
            val summarizer = mockk<AiSummarizer>()
            every { eventRepository.resetStuck(olderThan = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L))
            every { eventRepository.claimForSummary(id = 1L, token = any(), now = any()) } returns 0
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the summarizer is never invoked and nothing is marked done or failed") {
                    verify(exactly = 0) { summarizer.summarize(request = any()) }
                    verify(exactly = 0) { topicRepository.findById(id = any()) }
                    verify(exactly = 0) {
                        eventRepository.markDone(id = any(), token = any(), summary = any(), now = any())
                    }
                    verify(exactly = 0) { eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any()) }
                }
            }
        }

        given("an event whose summarizer throws, followed by a healthy event") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            val failToken = slot<String>()
            val nextAttemptAt = slot<LocalDateTime>()
            every { eventRepository.resetStuck(olderThan = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(
                    createCveEvent(id = 1L, topicId = 10L, retryCount = 2),
                    createCveEvent(id = 2L, topicId = 10L, retryCount = 0),
                )
            every { eventRepository.claimForSummary(id = 1L, token = capture(failToken), now = any()) } returns 1
            every { eventRepository.claimForSummary(id = 2L, token = any(), now = any()) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = match { it.eventId == 1L }) } throws
                AiSummarizationException(message = "boom")
            every { summarizer.summarize(request = match { it.eventId == 2L }) } returns "OK"
            every {
                eventRepository.markFailed(id = 1L, token = capture(failToken), nextAttemptAt = capture(nextAttemptAt))
            } returns 1
            every { eventRepository.markDone(id = 2L, token = any(), summary = "OK", now = any()) } returns 1
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                val before = LocalDateTime.now()
                worker.tick()
                val after = LocalDateTime.now()

                then("the failing event is marked failed with backoff = backoffMinutes * (retryCount + 1)") {
                    verify(exactly = 1) {
                        eventRepository.markFailed(id = 1L, token = any(), nextAttemptAt = any())
                    }
                    // retryCount 2 -> multiplier 3 -> +30 minutes from the failure moment.
                    val lowerBound = before.plusMinutes(backoffMinutes * 3).minusSeconds(5)
                    val upperBound = after.plusMinutes(backoffMinutes * 3).plusSeconds(5)
                    (nextAttemptAt.captured.isAfter(lowerBound)) shouldBe true
                    (nextAttemptAt.captured.isBefore(upperBound)) shouldBe true
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
            every { eventRepository.resetStuck(olderThan = any()) } returns 4
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns emptyList()
            val worker = workerWith(eventRepository)

            `when`("the tick runs") {
                worker.tick()

                then("stuck recovery still runs exactly once") {
                    verify(exactly = 1) { eventRepository.resetStuck(olderThan = any()) }
                }
            }
        }
        given("an event whose summarizer signals busy backpressure") {
            val eventRepository = mockk<CveEventRepository>(relaxed = true)
            val topicRepository = mockk<CveTopicRepository>()
            val summarizer = mockk<AiSummarizer>()
            every { eventRepository.resetStuck(olderThan = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L, retryCount = 3))
            every { eventRepository.claimForSummary(id = 1L, token = any(), now = any()) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = any()) } throws
                AiSummarizerBusyException(message = "Sidecar busy (code=busy) for event=1")
            every { eventRepository.releaseClaim(id = 1L, token = any(), nextAttemptAt = any()) } returns 1
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the claim is released without consuming the retry budget") {
                    verify(exactly = 1) {
                        eventRepository.releaseClaim(id = 1L, token = any(), nextAttemptAt = any())
                    }
                    verify(exactly = 0) { eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any()) }
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
            every { eventRepository.resetStuck(olderThan = any()) } returns 0
            every { eventRepository.findClaimable(now = any(), maxRetries = 5, limit = 10) } returns
                listOf(createCveEvent(id = 1L, topicId = 10L))
            every { eventRepository.claimForSummary(id = 1L, token = any(), now = any()) } returns 1
            every { topicRepository.findById(id = 10L) } returns createCveTopic(id = 10L)
            every { summarizer.summarize(request = any()) } returns "SUMMARY"
            every { eventRepository.markDone(id = 1L, token = any(), summary = "SUMMARY", now = any()) } returns 0
            val worker = workerWith(eventRepository, topicRepository, summarizer)

            `when`("the tick runs") {
                worker.tick()

                then("the lost claim is not treated as a failure — the retry budget stays intact") {
                    verify(exactly = 0) { eventRepository.markFailed(id = any(), token = any(), nextAttemptAt = any()) }
                    verify(
                        exactly = 0,
                    ) { eventRepository.releaseClaim(id = any(), token = any(), nextAttemptAt = any()) }
                }
            }
        }
    })
