package dev.notypie.application.service.standup

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.DateTimeException

class StandupSchedulerTest :
    BehaviorSpec({
        given("a scheduler tick") {
            `when`("the session-open phase throws (T2: one routine's overflowing cutoff)") {
                val schedulingService = mockk<StandupSchedulingService>()
                every { schedulingService.openSessionsForToday() } throws
                    DateTimeException("Instant exceeds minimum or maximum instant")
                every { schedulingService.sendPendingDispatches() } just Runs
                every { schedulingService.nudgeNonResponders() } just Runs
                every { schedulingService.detectCutoffs() } just Runs

                then("the tick swallows it and still sends DMs, nudges, and detects cutoffs") {
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
        }
    })
