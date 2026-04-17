package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.dto.isEmpty
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EmptyContextTest :
    BehaviorSpec({

        given("EmptyContext") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                EmptyContext(
                    commandBasicInfo = basicInfo,
                    requestHeaders = SlackRequestHeaders(),
                    intents = intentQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be SIMPLE") {
                    context.commandType shouldBe CommandType.SIMPLE
                }

                then("commandDetailType should be NOTHING") {
                    context.commandDetailType shouldBe CommandDetailType.NOTHING
                }
            }

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return empty CommandOutput") {
                    result.isEmpty() shouldBe true
                }

                then("should not add any intents to the queue") {
                    intentQueue.isEmpty() shouldBe true
                }
            }
        }
    })
