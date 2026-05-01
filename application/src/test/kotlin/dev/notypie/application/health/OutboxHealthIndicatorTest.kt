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

            // Default IN_PROGRESS counts to zero so each `when` block only stubs what it cares
            // about. Specific cases override these.
            every { repository.countInProgress() } returns 0L
            every { repository.countInProgressOlderThan(threshold = any()) } returns 0L
            every { repository.findOldestInProgressUpdatedAt() } returns null

            `when`("no PENDING or IN_PROGRESS messages exist") {
                every { repository.countPending() } returns 0L
                every { repository.countPendingOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestPendingCreatedAt() } returns null

                val result = indicator.health()

                then("status should be UP with zero counts on both states") {
                    result.status shouldBe Status.UP
                    result.details["pendingCount"] shouldBe 0L
                    result.details["stuckPendingCount"] shouldBe 0L
                    result.details["stuckCount"] shouldBe 0L // legacy alias
                    result.details["oldestPendingAgeSeconds"] shouldBe 0L
                    result.details["inFlightCount"] shouldBe 0L
                    result.details["stuckInFlightCount"] shouldBe 0L
                    result.details["oldestInFlightAgeSeconds"] shouldBe 0L
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
                    result.details["stuckPendingCount"] shouldBe 0L
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
                    result.details["stuckPendingCount"] shouldBe 2L
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

            `when`("an IN_PROGRESS row exceeds the stuck threshold") {
                val stuckClaim = now.minusSeconds(600L)
                // Pending side is healthy — the alert must come from in-flight stalling.
                every { repository.countPending() } returns 0L
                every { repository.countPendingOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestPendingCreatedAt() } returns null
                every { repository.countInProgress() } returns 1L
                every { repository.countInProgressOlderThan(threshold = any()) } returns 1L
                every { repository.findOldestInProgressUpdatedAt() } returns stuckClaim

                val result = indicator.health()

                then("status should be DOWN driven by the stuck in-flight row") {
                    result.status shouldBe Status.DOWN
                    result.details["pendingCount"] shouldBe 0L
                    result.details["stuckPendingCount"] shouldBe 0L
                    result.details["inFlightCount"] shouldBe 1L
                    result.details["stuckInFlightCount"] shouldBe 1L
                    result.details["oldestInFlightAgeSeconds"] shouldBe 600L
                }
            }

            `when`("there are IN_PROGRESS rows but none stuck (active dispatch)") {
                val freshClaim = now.minusSeconds(15L)
                every { repository.countPending() } returns 0L
                every { repository.countPendingOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestPendingCreatedAt() } returns null
                every { repository.countInProgress() } returns 4L
                every { repository.countInProgressOlderThan(threshold = any()) } returns 0L
                every { repository.findOldestInProgressUpdatedAt() } returns freshClaim

                val result = indicator.health()

                then("status should be UP — in-flight rows below threshold are normal") {
                    result.status shouldBe Status.UP
                    result.details["inFlightCount"] shouldBe 4L
                    result.details["stuckInFlightCount"] shouldBe 0L
                    result.details["oldestInFlightAgeSeconds"] shouldBe 15L
                }
            }
        }
    })
