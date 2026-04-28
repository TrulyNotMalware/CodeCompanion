package dev.notypie.application.health

import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class OutboxHealthIndicatorTest :
    BehaviorSpec({
        given("OutboxHealthIndicator") {
            val now = LocalDateTime.of(2026, 4, 28, 12, 0, 0)
            val zoneId = ZoneId.of("UTC")
            val clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), zoneId)
            val repository = mockk<MessageOutboxRepository>()
            val indicator =
                OutboxHealthIndicator(
                    outboxRepository = repository,
                    clock = clock,
                    stuckThresholdSeconds = 300L,
                )

            `when`("no PENDING messages exist") {
                every { repository.countPending() } returns 0L
                every { repository.countPendingOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestPendingCreatedAt() } returns null

                val result = indicator.health()

                then("status should be UP with zero counts") {
                    result.status shouldBe Status.UP
                    result.details["pendingCount"] shouldBe 0L
                    result.details["stuckCount"] shouldBe 0L
                    result.details["oldestPendingAgeSeconds"] shouldBe 0L
                    result.details["stuckThresholdSeconds"] shouldBe 300L
                }
            }

            `when`("there are PENDING messages but none stuck") {
                val freshAge = now.minusSeconds(60L)
                every { repository.countPending() } returns 3L
                every { repository.countPendingOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestPendingCreatedAt() } returns freshAge

                val result = indicator.health()

                then("status should be UP with reported lag") {
                    result.status shouldBe Status.UP
                    result.details["pendingCount"] shouldBe 3L
                    result.details["stuckCount"] shouldBe 0L
                    result.details["oldestPendingAgeSeconds"] shouldBe 60L
                }
            }

            `when`("at least one PENDING message exceeds the stuck threshold") {
                val stuckAge = now.minusSeconds(900L)
                every { repository.countPending() } returns 5L
                every { repository.countPendingOlderThan(threshold = any()) } returns 2L
                every { repository.findOldestPendingCreatedAt() } returns stuckAge

                val result = indicator.health()

                then("status should be DOWN with stuck details") {
                    result.status shouldBe Status.DOWN
                    result.details["pendingCount"] shouldBe 5L
                    result.details["stuckCount"] shouldBe 2L
                    result.details["oldestPendingAgeSeconds"] shouldBe 900L
                }
            }

            `when`("the oldest pending row reads as a future timestamp") {
                every { repository.countPending() } returns 1L
                every { repository.countPendingOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestPendingCreatedAt() } returns now.plusSeconds(30L)

                val result = indicator.health()

                then("the reported age should be clamped to zero rather than negative") {
                    result.details["oldestPendingAgeSeconds"] shouldBe 0L
                }
            }
        }
    })
