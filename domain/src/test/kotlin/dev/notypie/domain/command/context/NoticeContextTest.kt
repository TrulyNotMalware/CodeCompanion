package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.NoticeContext
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.LinkedList

class NoticeContextTest :
    BehaviorSpec({
        val testCommandBasicInfo = createCommandBasicInfo()

        given("NoticeContext with users and commands") {
            val users = LinkedList(listOf("U001", "U002"))
            val message = "deploy notify check"
            val intentQueue = createIntentQueue()

            val context =
                NoticeContext(
                    users = users,
                    message = message,
                    commandBasicInfo = testCommandBasicInfo,
                    intents = intentQueue,
                )

            `when`("parseCommandType is called") {
                then("should return SIMPLE") {
                    context.commandType shouldBe CommandType.SIMPLE
                }
            }

            `when`("parseCommandDetailType is called") {
                then("should return SIMPLE_TEXT") {
                    context.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT
                }
            }

            `when`("runCommand is called") {
                val result = context.runCommand()

                then("should return success result") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    result.commandType shouldBe CommandType.SIMPLE
                    result.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT
                }

                then("should add a Notice outbound to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val notice = intents.first().shouldBeInstanceOf<OutboundMessage.Notice>()
                    notice.target.id shouldBe testCommandBasicInfo.channel
                    notice.mentions shouldBe listOf(UserRef(id = "U001"), UserRef(id = "U002"))
                    notice.message shouldBe "deploy notify check"
                }
            }
        }

        given("NoticeContext keeps the message text as given") {
            val users = LinkedList(listOf("U001"))
            val message = "alpha beta\ngamma"
            val intentQueue = createIntentQueue()

            val context =
                NoticeContext(
                    users = users,
                    message = message,
                    commandBasicInfo = testCommandBasicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand is called") {
                context.runCommand()

                then("the notice carries the message with its line break") {
                    intentQueue
                        .snapshot()
                        .single()
                        .shouldBeInstanceOf<OutboundMessage.Notice>()
                        .message shouldBe
                        "alpha beta\ngamma"
                }
            }
        }

        given("NoticeContext with empty users and commands") {
            val users = LinkedList<String>()
            val message = ""
            val intentQueue = createIntentQueue()

            val context =
                NoticeContext(
                    users = users,
                    message = message,
                    commandBasicInfo = testCommandBasicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand is called with empty inputs") {
                val result = context.runCommand()

                then("should still return success") {
                    result.ok shouldBe true
                }

                then("should add a Notice outbound with empty mentions") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val notice = intents.first().shouldBeInstanceOf<OutboundMessage.Notice>()
                    notice.mentions shouldBe emptyList()
                }
            }
        }
    })
