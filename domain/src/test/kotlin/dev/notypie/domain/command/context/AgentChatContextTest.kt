package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.AgentChatContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AgentChatContextTest :
    BehaviorSpec({

        given("AgentChatContext with a prompt and a thread anchor") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                AgentChatContext(
                    prompt = "What meetings do I have today?",
                    threadId = TEST_THREAD_TS,
                    requesterName = TEST_USER_NAME,
                    channelName = TEST_CHANNEL_NAME,
                    commandBasicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("checking command metadata") {
                then("commandType should be PIPELINE") {
                    context.commandType shouldBe CommandType.PIPELINE
                }

                then("commandDetailType should be AGENT_CONVERSE") {
                    context.commandDetailType shouldBe CommandDetailType.AGENT_CONVERSE
                }
            }

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return success CommandOutput") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                }

                then("should add an AgentConverse intent carrying prompt, thread anchor, and names") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val intent = intents.first().shouldBeInstanceOf<CommandIntent.AgentConverse>()
                    intent.prompt shouldBe "What meetings do I have today?"
                    intent.threadId shouldBe TEST_THREAD_TS
                    intent.requesterName shouldBe TEST_USER_NAME
                    intent.channelName shouldBe TEST_CHANNEL_NAME
                }
            }
        }

        given("AgentChatContext without a thread anchor") {
            val intentQueue = createIntentQueue()

            val context =
                AgentChatContext(
                    prompt = "hello",
                    threadId = null,
                    requesterName = TEST_USER_NAME,
                    channelName = TEST_CHANNEL_NAME,
                    commandBasicInfo = createCommandBasicInfo(),
                    intents = intentQueue,
                )

            `when`("runCommand") {
                context.runCommand()

                then("the intent carries a null thread anchor (listener replies un-threaded)") {
                    intentQueue
                        .snapshot()
                        .first()
                        .shouldBeInstanceOf<CommandIntent.AgentConverse>()
                        .threadId shouldBe null
                }
            }
        }

        given("AgentChatContext with a blank prompt") {
            val intentQueue = createIntentQueue()
            val basicInfo = createCommandBasicInfo()

            val context =
                AgentChatContext(
                    prompt = "   ",
                    threadId = TEST_THREAD_TS,
                    requesterName = TEST_USER_NAME,
                    channelName = TEST_CHANNEL_NAME,
                    commandBasicInfo = basicInfo,
                    intents = intentQueue,
                )

            `when`("runCommand") {
                val result = context.runCommand()

                then("should return a failed CommandOutput") {
                    result.ok shouldBe false
                }

                then("should emit a guidance ephemeral instead of an AgentConverse intent") {
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val ephemeral = intents.first().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.target.id shouldBe basicInfo.channel
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        AgentChatContext.EMPTY_PROMPT_MESSAGE
                }
            }
        }
    })
