package dev.notypie.domain.command.parsers

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createEventCallbackData
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createRichTextBlock
import dev.notypie.domain.command.createSlackEventCallBackRequest
import dev.notypie.domain.command.createTextElement
import dev.notypie.domain.command.createUserElement
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.SlackNoticeContext
import dev.notypie.domain.command.entity.context.SlackStatusContext
import dev.notypie.domain.command.entity.context.SlackTextResponseContext
import dev.notypie.domain.command.entity.parsers.AppMentionContextParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class AppMentionContextParserTest :
    BehaviorSpec({
        val idempotencyKey = UUID.randomUUID()
        val intents = createIntentQueue()

        given("parseContext") {
            `when`("command is 'notice' with users") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(
                                            createUserElement(userId = TEST_USER_ID),
                                            createTextElement(text = " notice hello world"),
                                        ),
                                    ),
                            ),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackNoticeContext") {
                    result.shouldBeInstanceOf<SlackNoticeContext>()
                }
            }

            `when`("command is 'approval'") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(
                                            createTextElement(text = " approval"),
                                        ),
                                    ),
                            ),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackApprovalFormContext") {
                    result.shouldBeInstanceOf<SlackApprovalFormContext>()
                }
            }

            `when`("command is 'help'") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(
                                            createTextElement(text = " help"),
                                        ),
                                    ),
                            ),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackTextResponseContext for the help reply") {
                    result.shouldBeInstanceOf<SlackTextResponseContext>()
                }
            }

            `when`("command is 'status'") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(
                                            createTextElement(text = " status"),
                                        ),
                                    ),
                            ),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackStatusContext so the listener renders fresh metrics") {
                    result.shouldBeInstanceOf<SlackStatusContext>()
                }
            }

            `when`("command is unknown") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(
                                            createTextElement(text = " unknowncommand"),
                                        ),
                                    ),
                            ),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return DetailErrorAlertContext") {
                    result.shouldBeInstanceOf<DetailErrorAlertContext>()
                }
            }

            `when`("blocks have no rich_text element") {
                val body =
                    createSlackEventCallBackRequest(
                        event = createEventCallbackData(blocks = emptyList()),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackTextResponseContext (not supported)") {
                    result.shouldBeInstanceOf<SlackTextResponseContext>()
                }
            }

            `when`("command text is empty (only user mentions)") {
                val body =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                blocks =
                                    listOf(
                                        createRichTextBlock(
                                            createUserElement(userId = TEST_USER_ID),
                                        ),
                                    ),
                            ),
                    )
                val commandData = createAppMentionSlackCommandData(body = body)
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                then("should throw IllegalArgumentException (empty command queue)") {
                    shouldThrow<IllegalArgumentException> {
                        parser.parseContext(idempotencyKey = idempotencyKey)
                    }
                }
            }

            `when`("body is not SlackEventCallBackRequest") {
                val commandData =
                    createAppMentionSlackCommandData(
                        body = createSlackEventCallBackRequest(),
                    ).copy(body = "invalid body")
                val parser =
                    AppMentionContextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        intents = intents,
                    )

                then("should throw IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        parser.parseContext(idempotencyKey = idempotencyKey)
                    }
                }
            }
        }
    })
