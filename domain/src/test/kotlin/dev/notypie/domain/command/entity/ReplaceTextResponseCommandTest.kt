package dev.notypie.domain.command.entity

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class ReplaceTextResponseCommandTest :
    BehaviorSpec({
        val eventBuilder = mockEventBuilder(relaxed = true) {}
        val eventPublisher = mockk<EventPublisher>(relaxed = true)

        given("ReplaceTextResponseCommand") {
            val commandData = createAppMentionSlackCommandData()
            val idempotencyKey = UUID.randomUUID()

            val command =
                ReplaceTextResponseCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                    slackEventBuilder = eventBuilder,
                    eventPublisher = eventPublisher,
                    markdownMessage = "Replaced message content",
                    responseUrl = TEST_BASE_URL,
                )

            `when`("handleEvent") {
                val result = command.handleEvent()

                then("should return success") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("should publish events") {
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("findSubCommandDefinition") {
                val definition = command.findSubCommandDefinition()

                then("should return NoSubCommands") {
                    definition.shouldBeInstanceOf<NoSubCommands>()
                }
            }
        }
    })
