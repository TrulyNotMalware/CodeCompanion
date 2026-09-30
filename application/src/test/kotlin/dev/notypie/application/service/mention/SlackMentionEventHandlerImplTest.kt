package dev.notypie.application.service.mention

import dev.notypie.application.exception.AppIdNotFoundException
import dev.notypie.application.exception.UnsupportedSlackCommandTypeException
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.dto.response.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpHeaders
import org.springframework.util.LinkedMultiValueMap

class SlackMentionEventHandlerImplTest :
    BehaviorSpec({
        val commandExecutor = mockk<CommandExecutor>(relaxed = true)
        val commandRoleResolver = mockk<CommandRoleResolver>()

        val handler =
            SlackMentionEventHandlerImpl(
                commandExecutor = commandExecutor,
                commandRoleResolver = commandRoleResolver,
                transactionManager = createH2TransactionManager(),
            )

        val testHeaders =
            LinkedMultiValueMap<String, String>().apply {
                add(HttpHeaders.CONTENT_TYPE, "application/json")
            }

        given("parseAppMentionEvent") {
            `when`("payload contains api_app_id") {
                val payload = createAppMentionPayload()

                val result = handler.parseAppMentionEvent(headers = testHeaders, payload = payload)

                then("appId should match the payload value") {
                    result.appId shouldBe TEST_APP_ID
                }

                then("parsed command data fields should be correct") {
                    result.channel shouldBe TEST_CHANNEL_ID
                    result.actorId shouldBe TEST_USER_ID
                    result.appToken shouldBe TEST_BOT_TOKEN
                }
            }

            `when`("payload does not contain api_app_id") {
                val payload = createAppMentionPayload(appId = null)

                then("should throw AppIdNotFoundException") {
                    shouldThrow<AppIdNotFoundException> {
                        handler.parseAppMentionEvent(headers = testHeaders, payload = payload)
                    }
                }
            }

            `when`("payload type is not a known Slack event type") {
                val payload = createAppMentionPayload(type = "not_a_real_type")

                then("should throw UnsupportedSlackCommandTypeException") {
                    val exception =
                        shouldThrow<UnsupportedSlackCommandTypeException> {
                            handler.parseAppMentionEvent(headers = testHeaders, payload = payload)
                        }
                    exception.rawCommandType shouldBe "not_a_real_type"
                }
            }

            `when`("payload is a human-typed mention without bot metadata") {
                val payload = createAppMentionPayload(botId = null)

                val result = handler.parseAppMentionEvent(headers = testHeaders, payload = payload)

                then("parsing succeeds and carries the actor and channel") {
                    result.channel shouldBe TEST_CHANNEL_ID
                    result.actorId shouldBe TEST_USER_ID
                }
            }

            `when`("payload is an app-posted mention carrying bot metadata") {
                val payload = createAppMentionPayload(botId = "B001")

                val result = handler.parseAppMentionEvent(headers = testHeaders, payload = payload)

                then("parsing succeeds as before") {
                    result.channel shouldBe TEST_CHANNEL_ID
                    result.actorId shouldBe TEST_USER_ID
                }
            }

            `when`("payload has custom channel and publisher") {
                val payload =
                    createAppMentionPayload(
                        channel = "C_CUSTOM",
                        publisherId = "U_CUSTOM",
                        userName = "customuser",
                    )

                val result = handler.parseAppMentionEvent(headers = testHeaders, payload = payload)

                then("parsed values should reflect the custom parameters") {
                    result.channel shouldBe "C_CUSTOM"
                    result.actorId shouldBe "U_CUSTOM"
                    result.actorName shouldBe "customuser"
                }
            }

            // A9: a workflow or another app posting "@bot ..." sends neither `user` nor `blocks`; the non-null
            // fields failed deserialization with a 500 that Slack retried three times.
            `when`("payload is a text-only mention posted by a workflow (no user, no blocks)") {
                val payload = createAppMentionPayload(botId = "B_WORKFLOW").withoutEventKeys("user", "blocks")

                val result = handler.parseAppMentionEvent(headers = testHeaders, payload = payload)

                then("parsing succeeds with a blank actor instead of throwing") {
                    result.actorId shouldBe ""
                    result.channel shouldBe TEST_CHANNEL_ID
                }
            }

            `when`("payload carries no display names, as a real app_mention callback does") {
                val payload = createAppMentionPayload()

                val result = handler.parseAppMentionEvent(headers = testHeaders, payload = payload)

                then("names are blank rather than the literal string \"null\"") {
                    result.actorName shouldBe ""
                    result.channelName shouldBe ""
                }
            }
        }

        given("handleEvent(headers, payload)") {
            `when`("the mention was posted by an app or workflow (bot_id set)") {
                val executor = mockk<CommandExecutor>()
                val roleResolver = mockk<CommandRoleResolver>()
                val appHandler =
                    SlackMentionEventHandlerImpl(
                        commandExecutor = executor,
                        commandRoleResolver = roleResolver,
                        transactionManager = createH2TransactionManager(),
                    )

                val result =
                    appHandler.handleEvent(headers = testHeaders, payload = createAppMentionPayload(botId = "B_ANY"))

                then("it is acknowledged as a no-op: no role lookup, no command run (no self-reply loop)") {
                    result.status shouldBe Status.DO_NOTHING
                    verify(exactly = 0) { roleResolver.resolve(userId = any()) }
                    verify(exactly = 0) { executor.execute<SubCommandDefinition>(command = any()) }
                }
            }

            `when`("the mention carries no user at all") {
                val executor = mockk<CommandExecutor>()
                val appHandler =
                    SlackMentionEventHandlerImpl(
                        commandExecutor = executor,
                        commandRoleResolver = mockk(),
                        transactionManager = createH2TransactionManager(),
                    )

                val result =
                    appHandler.handleEvent(
                        headers = testHeaders,
                        payload = createAppMentionPayload().withoutEventKeys("user", "blocks"),
                    )

                then("it is dropped the same way") {
                    result.status shouldBe Status.DO_NOTHING
                    verify(exactly = 0) { executor.execute<SubCommandDefinition>(command = any()) }
                }
            }

            `when`("a person mentions the bot") {
                val executor = mockk<CommandExecutor>(relaxed = true)
                val roleResolver = mockk<CommandRoleResolver>()
                every { roleResolver.resolve(userId = TEST_USER_ID) } returns UserRole.USER
                val personHandler =
                    SlackMentionEventHandlerImpl(
                        commandExecutor = executor,
                        commandRoleResolver = roleResolver,
                        transactionManager = createH2TransactionManager(),
                    )

                personHandler.handleEvent(headers = testHeaders, payload = createAppMentionPayload())

                then("the command runs with the person's resolved role") {
                    verify(exactly = 1) { roleResolver.resolve(userId = TEST_USER_ID) }
                    verify(exactly = 1) { executor.execute<SubCommandDefinition>(command = any()) }
                }
            }
        }
    })

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.withoutEventKeys(vararg keys: String): Map<String, Any> =
    this + ("event" to ((this["event"] as Map<String, Any>) - keys.toSet()))
