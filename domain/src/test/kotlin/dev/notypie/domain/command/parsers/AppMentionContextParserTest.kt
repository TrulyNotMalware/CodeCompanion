package dev.notypie.domain.command.parsers

import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.entity.context.AgentChatContext
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.entity.context.NoticeContext
import dev.notypie.domain.command.entity.context.StatusContext
import dev.notypie.domain.command.entity.context.TextResponseContext
import dev.notypie.domain.command.entity.parsers.AppMentionContextParser
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.inbound.MessageHandle
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

/**
 * The rich_text flattening (bot filtering, token splitting) now lives in the infra mention mapper,
 * so this spec feeds a [MentionInvocation] directly and asserts routing only. The flattening golden
 * cases are ported to `SlackMentionMapperTest` in the infrastructure module.
 */
class AppMentionContextParserTest :
    BehaviorSpec({
        val idempotencyKey = UUID.randomUUID()
        val intents = createIntentQueue()

        fun createParser(mention: MentionInvocation, intentQueue: IntentQueue = intents): AppMentionContextParser =
            AppMentionContextParser(
                commandData = createMentionInboundCommand(),
                mention = mention,
                idempotencyKey = idempotencyKey,
                intents = intentQueue,
            )

        fun mentionOf(
            tokens: List<String>,
            userIds: List<String> = emptyList(),
            hasCommandStructure: Boolean = true,
            message: MessageHandle? = null,
            thread: MessageHandle? = null,
        ) = MentionInvocation(
            mentionedUserIds = userIds,
            commandTokens = tokens,
            hasCommandStructure = hasCommandStructure,
            message = message,
            thread = thread,
        )

        given("parseContext") {
            `when`("command is 'notice' with users") {
                val parser =
                    createParser(
                        mention =
                            mentionOf(
                                tokens = listOf("notice", "hello", "world"),
                                userIds = listOf(TEST_USER_ID),
                            ),
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return NoticeContext") {
                    result.shouldBeInstanceOf<NoticeContext>()
                }
            }

            `when`("command is 'approval'") {
                val parser = createParser(mention = mentionOf(tokens = listOf("approval")))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return ApprovalFormContext") {
                    result.shouldBeInstanceOf<ApprovalFormContext>()
                }
            }

            `when`("command is 'help'") {
                val parser = createParser(mention = mentionOf(tokens = listOf("help")))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return TextResponseContext for the help reply") {
                    result.shouldBeInstanceOf<TextResponseContext>()
                }
            }

            `when`("command is 'status'") {
                val parser = createParser(mention = mentionOf(tokens = listOf("status")))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return StatusContext so the listener renders fresh metrics") {
                    result.shouldBeInstanceOf<StatusContext>()
                }
            }

            `when`("command is 'ask' followed by a question") {
                val askIntents = createIntentQueue()
                val parser =
                    createParser(
                        mention =
                            mentionOf(
                                tokens = listOf("ask", "what", "is", "up"),
                                message = MessageHandle(raw = TEST_MESSAGE_TS),
                            ),
                        intentQueue = askIntents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return AgentChatContext") {
                    result.shouldBeInstanceOf<AgentChatContext>()
                }

                then("the keyword is stripped and the mention message anchors the conversation") {
                    result.runCommand()
                    val intent = askIntents.snapshot().first().shouldBeInstanceOf<CommandIntent.AgentConverse>()
                    intent.prompt shouldBe "what is up"
                    intent.threadId shouldBe TEST_MESSAGE_TS
                }
            }

            `when`("command is 'ask' inside an existing thread") {
                val askIntents = createIntentQueue()
                val parser =
                    createParser(
                        mention =
                            mentionOf(
                                tokens = listOf("ask", "continue"),
                                message = MessageHandle(raw = TEST_MESSAGE_TS),
                                thread = MessageHandle(raw = TEST_THREAD_TS),
                            ),
                        intentQueue = askIntents,
                    )

                parser.parseContext(idempotencyKey = idempotencyKey).runCommand()

                then("the enclosing thread wins as the conversation anchor") {
                    askIntents
                        .snapshot()
                        .first()
                        .shouldBeInstanceOf<CommandIntent.AgentConverse>()
                        .threadId shouldBe TEST_THREAD_TS
                }
            }

            `when`("command is unknown free text") {
                val fallbackIntents = createIntentQueue()
                val parser =
                    createParser(
                        mention = mentionOf(tokens = listOf("what", "does", "status", "mean")),
                        intentQueue = fallbackIntents,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should fall back to AgentChatContext") {
                    result.shouldBeInstanceOf<AgentChatContext>()
                }

                then("the full text — first token included — becomes the prompt") {
                    result.runCommand()
                    fallbackIntents
                        .snapshot()
                        .first()
                        .shouldBeInstanceOf<CommandIntent.AgentConverse>()
                        .prompt shouldBe "what does status mean"
                }
            }

            `when`("mention has no command structure") {
                val parser =
                    createParser(
                        mention = mentionOf(tokens = emptyList(), hasCommandStructure = false),
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return TextResponseContext (not supported)") {
                    result.shouldBeInstanceOf<TextResponseContext>()
                }
            }

            `when`("command structure is present but there are no tokens (only user mentions)") {
                val parser =
                    createParser(
                        mention = mentionOf(tokens = emptyList(), userIds = listOf(TEST_USER_ID)),
                    )

                then("should throw IllegalArgumentException (empty command queue)") {
                    shouldThrow<IllegalArgumentException> {
                        parser.parseContext(idempotencyKey = idempotencyKey)
                    }
                }
            }
        }
    })
