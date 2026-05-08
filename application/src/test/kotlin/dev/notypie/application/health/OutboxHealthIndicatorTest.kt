package dev.notypie.application.health

import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.stubOutboxStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.boot.health.contributor.Status

class OutboxHealthIndicatorTest :
    BehaviorSpec({
        given("OutboxHealthIndicator") {
            val now = DEFAULT_TEST_NOW
            val clock = createFixedUtcClock(now = now)
            val repository = mockk<MessageOutboxRepository>()
            val indicator =
                OutboxHealthIndicator(
                    outboxRepository = repository,
                    clock = clock,
                    stuckThresholdSeconds = 300L,
                )

            `when`("no PENDING or IN_PROGRESS messages exist") {
                repository.stubOutboxStatus()

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
                repository.stubOutboxStatus(
                    pendingCount = 3L,
                    oldestPendingCreatedAt = freshAge,
                )

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
                repository.stubOutboxStatus(
                    pendingCount = 5L,
                    stuckPendingCount = 2L,
                    oldestPendingCreatedAt = stuckAge,
                )

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
                repository.stubOutboxStatus(
                    pendingCount = 1L,
                    oldestPendingCreatedAt = now.plusSeconds(30L),
                )

                val result = indicator.health()

                then("the reported age should be clamped to zero rather than negative") {
                    result.details["oldestPendingAgeSeconds"] shouldBe 0L
                }
            }

            `when`("an IN_PROGRESS row exceeds the stuck threshold") {
                val stuckClaim = now.minusSeconds(600L)
                repository.stubOutboxStatus(
                    inProgressCount = 1L,
                    stuckInProgressCount = 1L,
                    oldestInProgressUpdatedAt = stuckClaim,
                )

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
                repository.stubOutboxStatus(
                    inProgressCount = 4L,
                    oldestInProgressUpdatedAt = freshClaim,
                )

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
