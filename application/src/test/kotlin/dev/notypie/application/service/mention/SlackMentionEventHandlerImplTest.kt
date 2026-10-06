package dev.notypie.application.service.mention

import dev.notypie.application.exception.AppIdNotFoundException
import dev.notypie.application.exception.InvalidEventPayloadException
import dev.notypie.application.exception.PayloadParseErrorCode
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

            `when`("the event body is missing a field the callback model requires") {
                val payload = createAppMentionPayload() - "event"

                then("it is an unreadable payload, which the advice answers with 400 and no retry") {
                    val exception =
                        shouldThrow<InvalidEventPayloadException> {
                            handler.parseAppMentionEvent(headers = testHeaders, payload = payload)
                        }
                    exception.errorCode shouldBe PayloadParseErrorCode.INVALID_EVENT_PAYLOAD
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
            fun handlerWith(executor: CommandExecutor, roleResolver: CommandRoleResolver) =
                SlackMentionEventHandlerImpl(
                    commandExecutor = executor,
                    commandRoleResolver = roleResolver,
                    transactionManager = createH2TransactionManager(),
                )

            `when`("the mention was posted by this app itself (its own reply echoing the bot mention)") {
                val executor = mockk<CommandExecutor>()
                val roleResolver = mockk<CommandRoleResolver>()

                val result =
                    handlerWith(executor = executor, roleResolver = roleResolver).handleEvent(
                        headers = testHeaders,
                        payload = createAppMentionPayload(botId = "B_SELF", botAppId = TEST_APP_ID),
                    )

                then("it is acknowledged as a no-op: no role lookup, no command run (no self-reply loop)") {
                    result.status shouldBe Status.DO_NOTHING
                    verify(exactly = 0) { roleResolver.resolve(userId = any()) }
                    verify(exactly = 0) { executor.execute<SubCommandDefinition>(command = any()) }
                }
            }

            `when`("only the bot profile names this app") {
                val executor = mockk<CommandExecutor>()

                val result =
                    handlerWith(executor = executor, roleResolver = mockk()).handleEvent(
                        headers = testHeaders,
                        payload = createAppMentionPayload(botId = "B_SELF").withoutEventKeys("app_id"),
                    )

                then("it is still recognised as our own message and dropped") {
                    result.status shouldBe Status.DO_NOTHING
                    verify(exactly = 0) { executor.execute<SubCommandDefinition>(command = any()) }
                }
            }

            `when`("the mention carries no user at all, as a workflow post does") {
                val executor = mockk<CommandExecutor>()

                val result =
                    handlerWith(executor = executor, roleResolver = mockk()).handleEvent(
                        headers = testHeaders,
                        payload = createAppMentionPayload().withoutEventKeys("user", "blocks"),
                    )

                then("it is dropped before parsing instead of failing deserialization") {
                    result.status shouldBe Status.DO_NOTHING
                    verify(exactly = 0) { executor.execute<SubCommandDefinition>(command = any()) }
                }
            }

            listOf(
                "a person mentions the bot" to createAppMentionPayload(),
                "a person mentions the bot through another app (bot_id and user set, foreign app id)" to
                    createAppMentionPayload(botId = "B_OTHER", botAppId = "A_OTHER_APP"),
            ).forEach { (case, payload) ->
                `when`(case) {
                    val executor = mockk<CommandExecutor>(relaxed = true)
                    val roleResolver = mockk<CommandRoleResolver>()
                    every { roleResolver.resolve(userId = TEST_USER_ID) } returns UserRole.USER

                    handlerWith(executor = executor, roleResolver = roleResolver)
                        .handleEvent(headers = testHeaders, payload = payload)

                    then("the command runs with the person's resolved role") {
                        verify(exactly = 1) { roleResolver.resolve(userId = TEST_USER_ID) }
                        verify(exactly = 1) { executor.execute<SubCommandDefinition>(command = any()) }
                    }
                }
            }
        }
    })
