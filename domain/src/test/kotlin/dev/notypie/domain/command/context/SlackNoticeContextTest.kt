package dev.notypie.domain.command.context

import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.SlackNoticeContext
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.dto.TestValidationData
import dev.notypie.domain.dto.shouldMatchExpected
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.LinkedList

class SlackNoticeContextTest :
    BehaviorSpec({
        val testCommandBasicInfo = createCommandBasicInfo()
        val eventBuilder =
            mockEventBuilder {
                SlackEventBuilder::simpleTextRequest returns
                    createSendSlackMessageEvent(
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    )
            }

        given("SlackNoticeContext with users and commands") {
            val users = LinkedList(listOf("U001", "U002"))
            val commands = LinkedList(listOf("deploy", "notify", "check"))
            val eventQueue = createDomainEventQueue()

            val context =
                SlackNoticeContext(
                    users = users,
                    commands = commands,
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
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
                    val validationData =
                        TestValidationData(
                            commandDetailType = context.commandDetailType,
                            commandType = context.commandType,
                            commandBasicInfo = testCommandBasicInfo,
                        )
                    (result shouldMatchExpected validationData) shouldBe true
                }

                then("should add event to the queue") {
                    val event = eventQueue.poll()
                    event shouldNotBe null
                    event?.type shouldBe CommandDetailType.SIMPLE_TEXT
                    event?.name shouldBe SendSlackMessageEvent::class.java.simpleName
                    event?.payload?.javaClass shouldBe PostEventPayloadContents::class.java
                    eventQueue.flushQueue()
                }
            }
        }

        given("SlackNoticeContext responseText uses space separator") {
            val users = LinkedList(listOf("U001"))
            val commands = LinkedList(listOf("alpha", "beta", "gamma"))
            val eventQueue = createDomainEventQueue()

            val context =
                SlackNoticeContext(
                    users = users,
                    commands = commands,
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
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
            val eventQueue = createDomainEventQueue()

            val context =
                SlackNoticeContext(
                    users = users,
                    commands = commands,
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                )

            `when`("runCommand is called with empty inputs") {
                val result = context.runCommand()

                then("should still return success") {
                    result.ok shouldBe true
                }

                then("should add event to the queue") {
                    val event = eventQueue.poll()
                    event shouldNotBe null
                    eventQueue.flushQueue()
                }
            }
        }
    })
