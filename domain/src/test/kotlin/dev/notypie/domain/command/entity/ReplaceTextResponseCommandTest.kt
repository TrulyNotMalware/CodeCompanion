package dev.notypie.domain.command.entity

import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.dto.response.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class ReplaceTextResponseCommandTest :
    BehaviorSpec({

        given("ReplaceTextResponseCommand") {
            val commandData = createMentionInboundCommand()
            val idempotencyKey = UUID.randomUUID()

            val command =
                ReplaceTextResponseCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                    markdownMessage = "Replaced message content",
                    replyHandle = TEST_BASE_URL,
                )

            `when`("handleEvent") {
                val result = command.handleEvent()

                then("should return success") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
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
