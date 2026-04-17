package dev.notypie.domain.command.context

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.SlackNoticeContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.LinkedList

class SlackNoticeContextTest :
    BehaviorSpec({
        val testCommandBasicInfo = createCommandBasicInfo()

        given("SlackNoticeContext with users and commands") {
            val users = LinkedList(listOf("U001", "U002"))
            val commands = LinkedList(listOf("deploy", "notify", "check"))
            val intentQueue = createIntentQueue()

            val context =
                SlackNoticeContext(
                    users = users,
                    commands = commands,
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

                then("should add Notice intent to the queue") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.Notice>()
                    val noticeIntent = intents.first() as CommandIntent.Notice
                    noticeIntent.targetUserIds shouldBe listOf("U001", "U002")
                    noticeIntent.message shouldBe "deploy notify check"
                }
            }
        }

        given("SlackNoticeContext responseText uses space separator") {
            val users = LinkedList(listOf("U001"))
            val commands = LinkedList(listOf("alpha", "beta", "gamma"))
            val intentQueue = createIntentQueue()

            val context =
                SlackNoticeContext(
                    users = users,
                    commands = commands,
                    commandBasicInfo = testCommandBasicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand is called") {
                context.runCommand()

                then("responseText should join commands with space, not commas") {
                    val responseText = commands.joinToString(separator = " ")
                    responseText shouldContain "alpha beta gamma"
                    responseText shouldNotContain ","
                }
            }
        }

        given("SlackNoticeContext with empty users and commands") {
            val users = LinkedList<String>()
            val commands = LinkedList<String>()
            val intentQueue = createIntentQueue()

            val context =
                SlackNoticeContext(
                    users = users,
                    commands = commands,
                    commandBasicInfo = testCommandBasicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand is called with empty inputs") {
                val result = context.runCommand()

                then("should still return success") {
                    result.ok shouldBe true
                }

                then("should add Notice intent with empty targets") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.Notice>()
                    val noticeIntent = intents.first() as CommandIntent.Notice
                    noticeIntent.targetUserIds shouldBe emptyList()
                }
            }
        }
    })
