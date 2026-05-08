package dev.notypie.domain.command.parsers

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createAppMentionSlackCommandData
import dev.notypie.domain.command.createAppMentionSlackCommandDataWithBlocks
import dev.notypie.domain.command.createAppMentionSlackCommandDataWithElements
import dev.notypie.domain.command.createAppMentionSlackCommandDataWithText
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createSlackEventCallBackRequest
import dev.notypie.domain.command.createUserElement
import dev.notypie.domain.command.dto.SlackCommandData
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

        fun createParser(commandData: SlackCommandData): AppMentionContextParser =
            AppMentionContextParser(
                slackCommandData = commandData,
                baseUrl = "",
                commandId = UUID.randomUUID(),
                idempotencyKey = idempotencyKey,
                intents = intents,
            )

        given("parseContext") {
            `when`("command is 'notice' with users") {
                val parser =
                    createParser(
                        commandData =
                            createAppMentionSlackCommandDataWithText(
                                text = " notice hello world",
                                userIds = listOf(TEST_USER_ID),
                            ),
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackNoticeContext") {
                    result.shouldBeInstanceOf<SlackNoticeContext>()
                }
            }

            `when`("command is 'approval'") {
                val parser = createParser(commandData = createAppMentionSlackCommandDataWithText(text = " approval"))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackApprovalFormContext") {
                    result.shouldBeInstanceOf<SlackApprovalFormContext>()
                }
            }

            `when`("command is 'help'") {
                val parser = createParser(commandData = createAppMentionSlackCommandDataWithText(text = " help"))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackTextResponseContext for the help reply") {
                    result.shouldBeInstanceOf<SlackTextResponseContext>()
                }
            }

            `when`("command is 'status'") {
                val parser = createParser(commandData = createAppMentionSlackCommandDataWithText(text = " status"))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackStatusContext so the listener renders fresh metrics") {
                    result.shouldBeInstanceOf<SlackStatusContext>()
                }
            }

            `when`("command is unknown") {
                val parser =
                    createParser(
                        commandData = createAppMentionSlackCommandDataWithText(text = " unknowncommand"),
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return DetailErrorAlertContext") {
                    result.shouldBeInstanceOf<DetailErrorAlertContext>()
                }
            }

            `when`("blocks have no rich_text element") {
                val parser =
                    createParser(
                        commandData = createAppMentionSlackCommandDataWithBlocks(blocks = emptyList()),
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackTextResponseContext (not supported)") {
                    result.shouldBeInstanceOf<SlackTextResponseContext>()
                }
            }

            `when`("command text is empty (only user mentions)") {
                val parser =
                    createParser(
                        commandData =
                            createAppMentionSlackCommandDataWithElements(
                                createUserElement(userId = TEST_USER_ID),
                            ),
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
                val parser = createParser(commandData = commandData)

                then("should throw IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        parser.parseContext(idempotencyKey = idempotencyKey)
                    }
                }
            }
        }
    })
