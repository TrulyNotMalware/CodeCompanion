package dev.notypie.application.service.cve.collector

import dev.notypie.impl.cve.SourceAdapter
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.cve.schema.CveSourceType
import dev.notypie.schema.createCveTopic
import dev.notypie.schema.createRawSourceEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class CveCollectorTest :
    BehaviorSpec({
        fun collectorWith(
            topicRepository: CveTopicRepository,
            eventRepository: CveEventRepository,
            ledgerRepository: CveCollectLedgerRepository,
            adapters: List<SourceAdapter>,
            clock: Clock = Clock.systemDefaultZone(),
        ) = CveCollector(
            cveTopicRepository = topicRepository,
            cveEventRepository = eventRepository,
            cveCollectLedgerRepository = ledgerRepository,
            adapters = adapters,
            windowMinutes = 5,
            clock = clock,
        )

        given("a topic whose window this instance claims") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            val ledgerRepository = mockk<CveCollectLedgerRepository>()
            val adapter = mockk<SourceAdapter>()
            val topic = createCveTopic(id = 7L, topicKey = "spring", sourceType = CveSourceType.GITHUB_RELEASE)
            val raw = createRawSourceEvent(externalId = "R1", title = "Title", rawContent = "Body", publishedAt = null)
            every { topicRepository.findActiveTopics() } returns listOf(topic)
            every { adapter.supports(sourceType = CveSourceType.GITHUB_RELEASE) } returns true
            every { ledgerRepository.claimWindow(topicId = 7L, windowStart = any()) } returns true
            every { ledgerRepository.deleteOlderThan(cutoff = any()) } returns 0
            every { adapter.fetch(topic = topic) } returns listOf(raw)
            every {
                eventRepository.insertIgnore(
                    topicId = 7L,
                    externalId = "R1",
                    title = "Title",
                    rawContent = "Body",
                    publishedAt = null,
                )
            } returns 1

            `when`("tick runs") {
                collectorWith(topicRepository, eventRepository, ledgerRepository, listOf(adapter)).tick()

                then("it fetches and ingests the raw event") {
                    verify(exactly = 1) { adapter.fetch(topic = topic) }
                    verify(exactly = 1) {
                        eventRepository.insertIgnore(
                            topicId = 7L,
                            externalId = "R1",
                            title = "Title",
                            rawContent = "Body",
                            publishedAt = null,
                        )
                    }
                }

                then("it prunes ledger rows past the retention horizon") {
                    verify(exactly = 1) { ledgerRepository.deleteOlderThan(cutoff = any()) }
                }
            }
        }

        given("two topics, and the tick's thread is interrupted while the first one is fetched") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            val ledgerRepository = mockk<CveCollectLedgerRepository>()
            val adapter = mockk<SourceAdapter>()
            val first = createCveTopic(id = 1L, topicKey = "first", sourceType = CveSourceType.NVD_CVE)
            val second = createCveTopic(id = 2L, topicKey = "second", sourceType = CveSourceType.NVD_CVE)
            every { topicRepository.findActiveTopics() } returns listOf(first, second)
            every { adapter.supports(sourceType = CveSourceType.NVD_CVE) } returns true
            every { ledgerRepository.claimWindow(topicId = any(), windowStart = any()) } returns true
            every { adapter.fetch(topic = first) } answers {
                Thread.currentThread().interrupt()
                throw InterruptedException("shutting down")
            }

            `when`("tick runs") {
                collectorWith(topicRepository, eventRepository, ledgerRepository, listOf(adapter)).tick()
                val keptInterrupt = Thread.interrupted()

                then("it stops before the second topic and leaves the interrupt set") {
                    verify(exactly = 0) { ledgerRepository.claimWindow(topicId = 2L, windowStart = any()) }
                    verify(exactly = 0) { adapter.fetch(topic = second) }
                    keptInterrupt shouldBe true
                }
            }
        }

        given("a tick on a clock fixed far from the wall clock") {
            val topicRepository = mockk<CveTopicRepository>()
            val ledgerRepository = mockk<CveCollectLedgerRepository>()
            val adapter = mockk<SourceAdapter>()
            val topic = createCveTopic(id = 9L, topicKey = "clocked", sourceType = CveSourceType.NVD_CVE)
            val fixedNow = LocalDateTime.of(2030, 3, 4, 5, 17, 42)
            val clock = Clock.fixed(fixedNow.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())
            every { topicRepository.findActiveTopics() } returns listOf(topic)
            every { adapter.supports(sourceType = CveSourceType.NVD_CVE) } returns true
            every { ledgerRepository.claimWindow(topicId = 9L, windowStart = any()) } returns false
            every { ledgerRepository.deleteOlderThan(cutoff = any()) } returns 0

            `when`("tick runs") {
                collectorWith(topicRepository, mockk(), ledgerRepository, listOf(adapter), clock = clock).tick()

                then("the claimed window and the ledger prune come from that clock") {
                    verify(exactly = 1) {
                        ledgerRepository.claimWindow(topicId = 9L, windowStart = LocalDateTime.of(2030, 3, 4, 5, 15))
                    }
                    verify(exactly = 1) {
                        ledgerRepository.deleteOlderThan(
                            cutoff = fixedNow.minusDays(CveCollector.LEDGER_RETENTION_DAYS),
                        )
                    }
                }
            }
        }

        given("a topic whose window a concurrent instance already claimed") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            val ledgerRepository = mockk<CveCollectLedgerRepository>()
            val adapter = mockk<SourceAdapter>()
            val topic = createCveTopic(id = 7L, topicKey = "spring", sourceType = CveSourceType.GITHUB_RELEASE)
            every { topicRepository.findActiveTopics() } returns listOf(topic)
            every { adapter.supports(sourceType = CveSourceType.GITHUB_RELEASE) } returns true
            every { ledgerRepository.claimWindow(topicId = 7L, windowStart = any()) } returns false
            every { ledgerRepository.deleteOlderThan(cutoff = any()) } returns 0

            `when`("tick runs") {
                collectorWith(topicRepository, eventRepository, ledgerRepository, listOf(adapter)).tick()

                then("it never fetches or ingests for the lost window") {
                    verify(exactly = 0) { adapter.fetch(topic = any()) }
                    verify(exactly = 0) {
                        eventRepository.insertIgnore(
                            topicId = any(),
                            externalId = any(),
                            title = any(),
                            rawContent = any(),
                            publishedAt = any(),
                        )
                    }
                }
            }
        }

        given("two topics where the first adapter fetch throws") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            val ledgerRepository = mockk<CveCollectLedgerRepository>()
            val adapter = mockk<SourceAdapter>()
            val failing = createCveTopic(id = 1L, topicKey = "a", sourceType = CveSourceType.GITHUB_RELEASE)
            val healthy = createCveTopic(id = 2L, topicKey = "b", sourceType = CveSourceType.GITHUB_RELEASE)
            val raw = createRawSourceEvent(externalId = "R2", title = "Title", rawContent = "Body", publishedAt = null)
            every { topicRepository.findActiveTopics() } returns listOf(failing, healthy)
            every { adapter.supports(sourceType = CveSourceType.GITHUB_RELEASE) } returns true
            every { ledgerRepository.claimWindow(topicId = any(), windowStart = any()) } returns true
            every { ledgerRepository.deleteOlderThan(cutoff = any()) } returns 0
            every { adapter.fetch(topic = failing) } throws RuntimeException("source down")
            every { adapter.fetch(topic = healthy) } returns listOf(raw)
            every {
                eventRepository.insertIgnore(
                    topicId = 2L,
                    externalId = "R2",
                    title = "Title",
                    rawContent = "Body",
                    publishedAt = null,
                )
            } returns 1

            `when`("tick runs") {
                collectorWith(topicRepository, eventRepository, ledgerRepository, listOf(adapter)).tick()

                then("the failure is isolated and the second topic is still collected") {
                    verify(exactly = 1) {
                        eventRepository.insertIgnore(
                            topicId = 2L,
                            externalId = "R2",
                            title = "Title",
                            rawContent = "Body",
                            publishedAt = null,
                        )
                    }
                }
            }
        }

        given("a topic whose source type no adapter supports") {
            val topicRepository = mockk<CveTopicRepository>()
            val eventRepository = mockk<CveEventRepository>()
            val ledgerRepository = mockk<CveCollectLedgerRepository>()
            val adapter = mockk<SourceAdapter>()
            val topic = createCveTopic(id = 9L, topicKey = "rss-feed", sourceType = CveSourceType.RSS)
            every { topicRepository.findActiveTopics() } returns listOf(topic)
            every { adapter.supports(sourceType = CveSourceType.RSS) } returns false
            every { ledgerRepository.deleteOlderThan(cutoff = any()) } returns 0

            `when`("tick runs") {
                collectorWith(topicRepository, eventRepository, ledgerRepository, listOf(adapter)).tick()

                then("the topic is skipped without claiming a window or fetching") {
                    verify(exactly = 0) { ledgerRepository.claimWindow(topicId = any(), windowStart = any()) }
                    verify(exactly = 0) { adapter.fetch(topic = any()) }
                }
            }
        }

        given("the window bucket math") {
            fun collectorWithWindow(windowMinutes: Long) =
                CveCollector(
                    cveTopicRepository = mockk(),
                    cveEventRepository = mockk(),
                    cveCollectLedgerRepository = mockk(),
                    adapters = emptyList(),
                    windowMinutes = windowMinutes,
                    clock = Clock.systemDefaultZone(),
                )

            `when`("a tick time falls inside a 5-minute bucket") {
                val windowStart =
                    collectorWithWindow(windowMinutes = 5)
                        .windowStart(now = LocalDateTime.of(2026, 7, 14, 10, 44, 47, 123_000_000))

                then("it truncates to the bucket start with zeroed seconds") {
                    windowStart shouldBe LocalDateTime.of(2026, 7, 14, 10, 40, 0)
                }
            }

            `when`("a tick time sits exactly on a bucket boundary") {
                val windowStart =
                    collectorWithWindow(windowMinutes = 5)
                        .windowStart(now = LocalDateTime.of(2026, 7, 14, 10, 45, 0))

                then("the boundary is its own bucket start") {
                    windowStart shouldBe LocalDateTime.of(2026, 7, 14, 10, 45, 0)
                }
            }

            `when`("a tick lands in the last bucket of the hour") {
                val windowStart =
                    collectorWithWindow(windowMinutes = 5)
                        .windowStart(now = LocalDateTime.of(2026, 7, 14, 10, 59, 59))

                then("the bucket never crosses the hour") {
                    windowStart shouldBe LocalDateTime.of(2026, 7, 14, 10, 55, 0)
                }
            }

            `when`("the window spans the whole hour") {
                val windowStart =
                    collectorWithWindow(windowMinutes = 60)
                        .windowStart(now = LocalDateTime.of(2026, 7, 14, 10, 31, 0))

                then("every minute maps to the top of the hour") {
                    windowStart shouldBe LocalDateTime.of(2026, 7, 14, 10, 0, 0)
                }
            }
        }
    })
