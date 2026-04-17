package dev.notypie.application.service.mention

import dev.notypie.application.exception.AppIdNotFoundException
import dev.notypie.application.exception.UnsupportedSlackCommandTypeException
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.history.HistoryHandler
import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.util.LinkedMultiValueMap

class SlackMentionEventHandlerImplTest :
    BehaviorSpec({
        val historyHandler = mockk<HistoryHandler>(relaxed = true)
        val commandExecutor = mockk<CommandExecutor>(relaxed = true)

        val handler =
            SlackMentionEventHandlerImpl(
                historyHandler = historyHandler,
                commandExecutor = commandExecutor,
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
                    result.publisherId shouldBe TEST_USER_ID
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

            `when`("payload type is not a known SlackCommandType") {
                val payload = createAppMentionPayload(type = "not_a_real_type")

                then("should throw UnsupportedSlackCommandTypeException") {
                    val exception =
                        shouldThrow<UnsupportedSlackCommandTypeException> {
                            handler.parseAppMentionEvent(headers = testHeaders, payload = payload)
                        }
                    exception.rawCommandType shouldBe "not_a_real_type"
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
                    result.publisherId shouldBe "U_CUSTOM"
                }
            }
        }
    })
