package dev.notypie.impl.command.slack

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.MentionInvocation
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Golden coverage for the app_mention rich_text flattening, ported verbatim from the old domain
 * `AppMentionContextParserTest`. Routing on the flattened result now lives in the domain parser
 * spec; this spec pins the boundary flattening: bot-id filtering, whitespace token splitting with
 * blanks dropped, and the two distinct "no command" shapes (no structure vs. structure-without-tokens).
 */
class SlackMentionMapperTest :
    BehaviorSpec({
        val botId = "B_BOT"

        fun mapWith(vararg elements: Element): MentionInvocation {
            val request =
                createSlackEventCallBackRequest(
                    event = createEventCallbackData(blocks = listOf(createRichTextBlock(elements = elements))),
                    authorizations = listOf(createAuthorization(userId = botId, isBot = true)),
                )
            val command =
                request.toMentionInboundCommand(appId = TEST_APP_ID, channelName = "general", actorName = "tester")
            return command.payload.shouldBeInstanceOf<MentionInvocation>()
        }

        given("a rich_text mention mixing user mentions and command text") {
            `when`("flattened") {
                val mention =
                    mapWith(
                        createUserElement(userId = botId),
                        createUserElement(userId = "U_OTHER"),
                        createTextElement(text = " notice hello world"),
                    )

                then("the bot's own user id is filtered out, other mentions are kept") {
                    mention.mentionedUserIds shouldBe listOf("U_OTHER")
                }

                then("command text is split on spaces with blanks dropped") {
                    mention.commandTokens shouldBe listOf("notice", "hello", "world")
                }

                then("a command structure was detected") {
                    mention.hasCommandStructure shouldBe true
                }
            }
        }

        given("command text padded with irregular whitespace") {
            `when`("flattened") {
                val mention = mapWith(createTextElement(text = "   status    now   "))

                then("only non-blank tokens survive") {
                    mention.commandTokens shouldBe listOf("status", "now")
                }
            }
        }

        given("an event with no rich_text block (empty blocks)") {
            `when`("flattened") {
                val request =
                    createSlackEventCallBackRequest(
                        event = createEventCallbackData(blocks = emptyList()),
                        authorizations = listOf(createAuthorization(userId = botId, isBot = true)),
                    )
                val command =
                    request.toMentionInboundCommand(appId = TEST_APP_ID, channelName = "general", actorName = "tester")
                val mention = command.payload.shouldBeInstanceOf<MentionInvocation>()

                then("no command structure is reported, so the domain replies 'not supported'") {
                    mention.hasCommandStructure shouldBe false
                    mention.commandTokens shouldBe emptyList()
                    mention.mentionedUserIds shouldBe emptyList()
                    command.kind shouldBe InboundKind.MENTION
                }
            }
        }

        given("a rich_text section carrying only user mentions and no command text") {
            `when`("flattened") {
                val mention = mapWith(createUserElement(userId = "U_OTHER"))

                then("structure is present but there are zero tokens (domain then throws)") {
                    mention.hasCommandStructure shouldBe true
                    mention.commandTokens shouldBe emptyList()
                    mention.mentionedUserIds shouldBe listOf("U_OTHER")
                }
            }
        }

        given("a top-level mention (no thread_ts)") {
            `when`("flattened") {
                val request =
                    createSlackEventCallBackRequest(
                        event = createEventCallbackData(ts = "1712345678.000100"),
                        authorizations = listOf(createAuthorization(userId = botId, isBot = true)),
                    )
                val mention =
                    request
                        .toMentionInboundCommand(appId = TEST_APP_ID, channelName = "general", actorName = "tester")
                        .payload
                        .shouldBeInstanceOf<MentionInvocation>()

                then("the mention message ts is carried and there is no thread handle") {
                    mention.message?.raw shouldBe "1712345678.000100"
                    mention.thread shouldBe null
                }
            }
        }

        given("a mention inside an existing thread") {
            `when`("flattened") {
                val request =
                    createSlackEventCallBackRequest(
                        event =
                            createEventCallbackData(
                                ts = "1712345678.000100",
                                threadTs = "1712345600.000200",
                            ),
                        authorizations = listOf(createAuthorization(userId = botId, isBot = true)),
                    )
                val mention =
                    request
                        .toMentionInboundCommand(appId = TEST_APP_ID, channelName = "general", actorName = "tester")
                        .payload
                        .shouldBeInstanceOf<MentionInvocation>()

                then("both the message ts and the enclosing thread root are carried") {
                    mention.message?.raw shouldBe "1712345678.000100"
                    mention.thread?.raw shouldBe "1712345600.000200"
                }
            }
        }
    })
