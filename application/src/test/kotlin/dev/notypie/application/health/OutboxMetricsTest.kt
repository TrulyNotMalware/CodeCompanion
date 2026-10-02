package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.stubOutboxStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException
import java.util.concurrent.TimeUnit

class OutboxMetricsTest :
    BehaviorSpec({
        val now = DEFAULT_TEST_NOW

        fun metricsOver(repository: MessageOutboxRepository): SimpleMeterRegistry =
            SimpleMeterRegistry().also { registry ->
                OutboxMetrics(
                    outboxRepository = repository,
                    clock = createFixedUtcClock(now = now),
                    appConfig =
                        AppConfig(
                            outbox = AppConfig.Outbox(health = AppConfig.Outbox.Health(retryingSendThreshold = 4)),
                        ),
                    meterRegistry = registry,
                )
            }

        fun SimpleMeterRegistry.gaugeValue(name: String, vararg tags: String): Double =
            get(name).tags(*tags).gauge().value()

        fun SimpleMeterRegistry.ageSeconds(name: String): Double = get(name).timeGauge().value(TimeUnit.SECONDS)

        given("an outbox with pending, in-flight and retrying rows") {
            val repository = mockk<MessageOutboxRepository>()
            repository.stubOutboxStatus(
                pendingCount = 5L,
                oldestPendingCreatedAt = now.minusSeconds(420L),
                inProgressCount = 2L,
                oldestInProgressUpdatedAt = now.minusSeconds(75L),
                retryingCount = 1L,
            )
            val registry = metricsOver(repository = repository)

            `when`("the gauges are read") {
                then("they report the counts and the ages from the repository and the clock") {
                    registry.gaugeValue(OUTBOX_MESSAGES_METRIC, "status", "pending") shouldBe 5.0
                    registry.gaugeValue(OUTBOX_MESSAGES_METRIC, "status", "in_progress") shouldBe 2.0
                    registry.ageSeconds(OUTBOX_PENDING_OLDEST_AGE_METRIC) shouldBe 420.0
                    registry.ageSeconds(OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC) shouldBe 75.0
                    registry.gaugeValue(OUTBOX_RETRYING_METRIC) shouldBe 1.0
                    verify { repository.countInProgressWithSendsAtLeast(sends = 4) }
                }
            }
        }

        given("a scrape that reads every gauge") {
            val repository = mockk<MessageOutboxRepository>()
            repository.stubOutboxStatus(pendingCount = 1L)
            val registry = metricsOver(repository = repository)

            `when`("the five gauges are read one after another") {
                registry.gaugeValue(OUTBOX_MESSAGES_METRIC, "status", "pending")
                registry.gaugeValue(OUTBOX_MESSAGES_METRIC, "status", "in_progress")
                registry.ageSeconds(OUTBOX_PENDING_OLDEST_AGE_METRIC)
                registry.ageSeconds(OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC)
                registry.gaugeValue(OUTBOX_RETRYING_METRIC)

                then("they share one snapshot instead of querying the outbox once per gauge") {
                    verify(exactly = 1) { repository.countPending() }
                    verify(exactly = 1) { repository.countInProgress() }
                    verify(exactly = 1) { repository.countInProgressWithSendsAtLeast(sends = 4) }
                }
            }
        }

        given("an empty outbox") {
            val repository = mockk<MessageOutboxRepository>()
            repository.stubOutboxStatus()
            val registry = metricsOver(repository = repository)

            `when`("the age gauges are read") {
                then("they report zero rather than a missing value") {
                    registry.ageSeconds(OUTBOX_PENDING_OLDEST_AGE_METRIC) shouldBe 0.0
                    registry.ageSeconds(OUTBOX_IN_PROGRESS_OLDEST_CLAIM_AGE_METRIC) shouldBe 0.0
                }
            }
        }

        given("a database that cannot be reached") {
            val repository = mockk<MessageOutboxRepository>()
            repository.stubOutboxStatus()
            every { repository.findOldestPendingCreatedAt() } throws DataAccessResourceFailureException("down")
            val registry = metricsOver(repository = repository)

            `when`("the oldest-pending gauge is read") {
                then("it reports NaN instead of a stale or zero age") {
                    registry.ageSeconds(OUTBOX_PENDING_OLDEST_AGE_METRIC).shouldBeNaN()
                }
            }
        }
    })
