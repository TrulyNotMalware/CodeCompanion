package dev.notypie.application.health

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.stubOutboxStatus
import dev.notypie.application.service.relay.AccessBlockedTracker
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.springframework.boot.health.contributor.Status

class OutboxHealthIndicatorTest :
    BehaviorSpec({
        given("OutboxHealthIndicator") {
            val now = DEFAULT_TEST_NOW
            val clock = createFixedUtcClock(now = now)
            val repository = mockk<MessageOutboxRepository>()
            val accessBlockedTracker = AccessBlockedTracker()
            val indicator =
                OutboxHealthIndicator(
                    outboxRepository = repository,
                    clock = clock,
                    accessBlockedTracker = accessBlockedTracker,
                    appConfig =
                        AppConfig(
                            outbox =
                                AppConfig.Outbox(
                                    health =
                                        AppConfig.Outbox.Health(
                                            stuckThresholdSeconds = 300L,
                                            retryingSendThreshold = 3,
                                        ),
                                ),
                        ),
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
                    result.details["retryingCount"] shouldBe 0L
                    result.details["retryingSendThreshold"] shouldBe 3
                    result.details["accessBlocked"] shouldBe false
                    result.details["lastAccessBlockedAt"] shouldBe "never"
                    result.details["accessBlockedWindowSeconds"] shouldBe 1_200L
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

            `when`("a rate-limited row was deferred and waits for the recovery sweep") {
                repository.stubOutboxStatus(inProgressCount = 1L, oldestInProgressUpdatedAt = now.minusSeconds(320L))

                val result = indicator.health()

                then("a row the sweep may still pick up within one period is not stuck, and sends are the budget") {
                    result.status shouldBe Status.UP
                    verify { repository.countInProgressOlderThan(threshold = now.minusSeconds(360L)) }
                    verify { repository.countInProgressWithSendsAtLeast(sends = 3) }
                }
            }

            // Review F6: the held row has its send taken back and updated_at moved ahead, so no count sees it.
            `when`("the relay held a row for a Slack access error inside the window, and only that row is in flight") {
                repository.stubOutboxStatus(inProgressCount = 1L)
                accessBlockedTracker.record(at = clock.instant().minusSeconds(1_100L))

                val result = indicator.health()

                then("status is DOWN on the access block alone") {
                    result.status shouldBe Status.DOWN
                    result.details["stuckInFlightCount"] shouldBe 0L
                    result.details["retryingCount"] shouldBe 0L
                    result.details["accessBlocked"] shouldBe true
                    result.details["lastAccessBlockedAt"] shouldBe "2026-04-28T11:41:40Z"
                }
            }

            `when`("the last access block is older than the window") {
                repository.stubOutboxStatus()
                val tracker = AccessBlockedTracker().apply { record(at = clock.instant().minusSeconds(1_200L)) }
                val result =
                    OutboxHealthIndicator(outboxRepository = repository, clock = clock, accessBlockedTracker = tracker)
                        .health()

                then("status is back to UP") {
                    result.status shouldBe Status.UP
                    result.details["accessBlocked"] shouldBe false
                }
            }

            `when`("a row keeps being reclaimed and was just re-claimed, so its age looks fresh") {
                val freshReclaim = now.minusSeconds(5L)
                repository.stubOutboxStatus(
                    inProgressCount = 1L,
                    oldestInProgressUpdatedAt = freshReclaim,
                    retryingCount = 1L,
                )

                val result = indicator.health()

                then("status stays DOWN on the send count instead of flapping back to UP") {
                    result.status shouldBe Status.DOWN
                    result.details["stuckInFlightCount"] shouldBe 0L
                    result.details["retryingCount"] shouldBe 1L
                    verify(atLeast = 1) { repository.countInProgressWithSendsAtLeast(sends = 3) }
                }
            }
        }
    })
