package dev.notypie.domain.command.parsers

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.entity.context.DetailErrorAlertContext
import dev.notypie.domain.command.entity.context.NoticeContext
import dev.notypie.domain.command.entity.context.StatusContext
import dev.notypie.domain.command.entity.context.TextResponseContext
import dev.notypie.domain.command.entity.parsers.AppMentionContextParser
import dev.notypie.domain.command.inbound.MentionInvocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
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

        fun createParser(mention: MentionInvocation): AppMentionContextParser =
            AppMentionContextParser(
                commandData = createMentionInboundCommand(),
                mention = mention,
                baseUrl = "",
                commandId = UUID.randomUUID(),
                idempotencyKey = idempotencyKey,
                intents = intents,
            )

        fun mentionOf(tokens: List<String>, userIds: List<String> = emptyList(), hasCommandStructure: Boolean = true) =
            MentionInvocation(
                mentionedUserIds = userIds,
                commandTokens = tokens,
                hasCommandStructure = hasCommandStructure,
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

            `when`("command is unknown") {
                val parser = createParser(mention = mentionOf(tokens = listOf("unknowncommand")))

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return DetailErrorAlertContext") {
                    result.shouldBeInstanceOf<DetailErrorAlertContext>()
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
