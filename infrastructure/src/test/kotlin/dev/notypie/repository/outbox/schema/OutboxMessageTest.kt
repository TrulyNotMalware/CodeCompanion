package dev.notypie.repository.outbox.schema

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createActionEventPayloadContents
import dev.notypie.domain.command.createPostEventPayloadContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class OutboxMessageTest :
    BehaviorSpec({
        val testIdempotencyKey = UUID.randomUUID()

        given("PostEventPayloadContents.toOutboxMessage") {
            val postEvent =
                createPostEventPayloadContents(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    idempotencyKey = testIdempotencyKey,
                    body = mapOf("text" to "hello"),
                )

            `when`("converted to outbox message") {
                val result = postEvent.toOutboxMessage()

                then("outbox message fields should match the event") {
                    val outbox = result.outboxMessage
                    outbox.idempotencyKey shouldBe testIdempotencyKey.toString()
                    outbox.publisherId shouldBe TEST_USER_ID
                    outbox.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT.name
                    outbox.type shouldBe MessageType.CHANNEL_ALERT.name
                    outbox.payload shouldBe mapOf("text" to "hello")
                }

                then("metadata should contain api_app_id, channel, and replace_original") {
                    val metadata = result.outboxMessage.metadata
                    metadata["api_app_id"] shouldBe TEST_APP_ID
                    metadata["channel"] shouldBe TEST_CHANNEL_ID
                    metadata["replace_original"] shouldBe false
                }

                then("reason should be PostEventContents") {
                    result.reason shouldBe "PostEventContents"
                }

                then("slackEventPayload should be the original event") {
                    result.slackEventPayload shouldBe postEvent
                }
            }
        }

        given("ActionEventPayloadContents.toOutboxMessage") {
            val actionEvent =
                createActionEventPayloadContents(
                    commandDetailType = CommandDetailType.REPLACE_TEXT,
                    idempotencyKey = testIdempotencyKey,
                    body = """{"text":"hello"}""",
                )

            `when`("converted to outbox message") {
                val result = actionEvent.toOutboxMessage()

                then("outbox message fields should match the event") {
                    val outbox = result.outboxMessage
                    outbox.idempotencyKey shouldBe testIdempotencyKey.toString()
                    outbox.publisherId shouldBe TEST_USER_ID
                    outbox.commandDetailType shouldBe CommandDetailType.REPLACE_TEXT.name
                    outbox.type shouldBe MessageType.ACTION_RESPONSE.name
                }

                then("payload should be deserialized from JSON body string") {
                    val payload = result.outboxMessage.payload
                    payload shouldNotBe null
                    payload["text"] shouldBe "hello"
                }

                then("metadata should contain api_app_id, channel, and response_url") {
                    val metadata = result.outboxMessage.metadata
                    metadata["api_app_id"] shouldBe TEST_APP_ID
                    metadata["channel"] shouldBe TEST_CHANNEL_ID
                    metadata["response_url"] shouldBe TEST_BASE_URL
                }

                then("reason should be ActionEventContents") {
                    result.reason shouldBe "ActionEventContents"
                }

                then("slackEventPayload should be the original event") {
                    result.slackEventPayload shouldBe actionEvent
                }
            }
        }

        given("OutboxMessage.toSlackEvent") {
            `when`("converting a post type outbox message back to SlackEventPayload") {
                val postEvent =
                    createPostEventPayloadContents(
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        idempotencyKey = testIdempotencyKey,
                        body = mapOf("text" to "hello"),
                    )
                val outboxMessage = postEvent.toOutboxMessage().outboxMessage
                val restored = outboxMessage.toSlackEvent()

                then("restored event should be PostEventPayloadContents") {
                    restored.shouldBeInstanceOf<PostEventPayloadContents>()
                }

                then("restored event fields should match the original") {
                    restored.apiAppId shouldBe TEST_APP_ID
                    restored.publisherId shouldBe TEST_USER_ID
                    restored.channel shouldBe TEST_CHANNEL_ID
                    restored.idempotencyKey shouldBe testIdempotencyKey
                    restored.commandDetailType shouldBe CommandDetailType.SIMPLE_TEXT
                }
            }

            `when`("converting an action type outbox message back to SlackEventPayload") {
                val actionEvent =
                    createActionEventPayloadContents(
                        commandDetailType = CommandDetailType.REPLACE_TEXT,
                        idempotencyKey = testIdempotencyKey,
                        body = """{"text":"hello"}""",
                    )
                val outboxMessage = actionEvent.toOutboxMessage().outboxMessage
                val restored = outboxMessage.toSlackEvent()

                then("restored event should be ActionEventPayloadContents") {
                    restored.shouldBeInstanceOf<ActionEventPayloadContents>()
                }

                then("restored event fields should match the original") {
                    restored.apiAppId shouldBe TEST_APP_ID
                    restored.publisherId shouldBe TEST_USER_ID
                    restored.channel shouldBe TEST_CHANNEL_ID
                    restored.idempotencyKey shouldBe testIdempotencyKey
                    restored.commandDetailType shouldBe CommandDetailType.REPLACE_TEXT
                }
            }
        }

        given("OutboxMessage.updateMessageStatus") {
            val postEvent =
                createPostEventPayloadContents(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    idempotencyKey = testIdempotencyKey,
                )
            val outboxMessage = postEvent.toOutboxMessage().outboxMessage

            `when`("status is updated to SUCCESS") {
                outboxMessage.updateMessageStatus(status = MessageStatus.SUCCESS)

                then("status should be SUCCESS") {
                    outboxMessage.status shouldBe MessageStatus.SUCCESS.name
                }
            }

            `when`("status is updated to FAILURE") {
                outboxMessage.updateMessageStatus(status = MessageStatus.FAILURE)

                then("status should be FAILURE") {
                    outboxMessage.status shouldBe MessageStatus.FAILURE.name
                }
            }
        }
    })
