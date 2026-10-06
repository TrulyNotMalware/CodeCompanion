package dev.notypie.application.service.standup

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.DateTimeException

class StandupSchedulerTest :
    BehaviorSpec({
        given("a scheduler tick") {
            `when`("the session-open phase throws") {
                val schedulingService = mockk<StandupSchedulingService>()
                every { schedulingService.openSessionsForToday() } throws
                    DateTimeException("Instant exceeds minimum or maximum instant")
                every { schedulingService.sendPendingDispatches() } just Runs
                every { schedulingService.nudgeNonResponders() } just Runs
                every { schedulingService.detectCutoffs() } just Runs

                then("the tick contains it and still sends DMs, nudges and detects cutoffs") {
                    shouldNotThrowAny { StandupScheduler(schedulingService = schedulingService).tick() }
                    verify(exactly = 1) { schedulingService.sendPendingDispatches() }
                    verify(exactly = 1) { schedulingService.nudgeNonResponders() }
                    verify(exactly = 1) { schedulingService.detectCutoffs() }
                }
            }

            `when`("every phase throws") {
                val schedulingService = mockk<StandupSchedulingService>()
                every { schedulingService.openSessionsForToday() } throws IllegalStateException("open")
                every { schedulingService.sendPendingDispatches() } throws IllegalStateException("send")
                every { schedulingService.nudgeNonResponders() } throws IllegalStateException("nudge")
                every { schedulingService.detectCutoffs() } throws IllegalStateException("cutoff")

                then("each phase is still attempted exactly once") {
                    shouldNotThrowAny { StandupScheduler(schedulingService = schedulingService).tick() }
                    verify(exactly = 1) { schedulingService.openSessionsForToday() }
                    verify(exactly = 1) { schedulingService.sendPendingDispatches() }
                    verify(exactly = 1) { schedulingService.nudgeNonResponders() }
                    verify(exactly = 1) { schedulingService.detectCutoffs() }
                }
            }

            `when`("a phase is interrupted") {
                val schedulingService = mockk<StandupSchedulingService>()
                every { schedulingService.openSessionsForToday() } throws InterruptedException("shutdown")
                every { schedulingService.sendPendingDispatches() } just Runs

                val escaped =
                    runCatching {
                        StandupScheduler(
                            schedulingService = schedulingService,
                        ).tick()
                    }.exceptionOrNull()
                val interruptFlag = Thread.interrupted()

                then("the interrupt ends the tick with the flag restored instead of running the later phases") {
                    escaped.shouldBeInstanceOf<InterruptedException>()
                    interruptFlag shouldBe true
                    verify(exactly = 0) { schedulingService.sendPendingDispatches() }
                }
            }

            `when`("a phase fails with an Error") {
                val schedulingService = mockk<StandupSchedulingService>()
                every { schedulingService.openSessionsForToday() } throws OutOfMemoryError("heap")

                then("it is not contained") {
                    runCatching { StandupScheduler(schedulingService = schedulingService).tick() }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<OutOfMemoryError>()
                }
            }
        }
    })
