package dev.notypie.impl.command.slack

import dev.notypie.common.jsonMapper
import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.command.inbound.InboundKind
import dev.notypie.domain.command.inbound.MentionInvocation
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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

        // T24: only the first section's text/user leaves were read, so links, code blocks, other people's mentions
        // and everything after the first section never reached the agent prompt; a styled word failed parsing.
        given("an app_mention whose rich_text mixes links, styles, code, a quote and a list") {
            val payload =
                jsonMapper.readValue(
                    """
                    {
                      "token": "t", "team_id": "T1", "api_app_id": "A1", "type": "event_callback",
                      "event_id": "Ev1", "event_time": "1", "is_ext_shared_channel": false, "event_context": "c",
                      "authorizations": [{"enterprise_id": null, "team_id": "T1", "user_id": "$botId",
                        "is_bot": true, "is_enterprise_install": false}],
                      "event": {
                        "type": "app_mention", "user": "U_ASKER", "ts": "1712345678.000100", "team": "T1",
                        "channel": "C1", "event_ts": 1712345678.0001,
                        "text": "<@$botId> ask ...",
                        "blocks": [{"type": "rich_text", "block_id": "b1", "elements": [
                          {"type": "rich_text_section", "elements": [
                            {"type": "user", "user_id": "$botId"},
                            {"type": "text", "text": " ask "},
                            {"type": "text", "text": "why", "style": {"bold": true}},
                            {"type": "text", "text": " does "},
                            {"type": "text", "text": "deploy", "style": {"code": true}},
                            {"type": "text", "text": " fail? see "},
                            {"type": "link", "url": "https://ci.example/run/42", "text": "run 42"},
                            {"type": "text", "text": " and "},
                            {"type": "link", "url": "https://ci.example/log"},
                            {"type": "text", "text": ", cc "},
                            {"type": "user", "user_id": "U_ALICE"},
                            {"type": "text", "text": " in "},
                            {"type": "channel", "channel_id": "C_OPS"},
                            {"type": "text", "text": " "},
                            {"type": "emoji", "name": "fire", "unicode": "1f525"},
                            {"type": "text", "text": "\n"}
                          ]},
                          {"type": "rich_text_preformatted", "border": 0, "elements": [
                            {"type": "text", "text": "Error: exit 1\nat step build"}
                          ]},
                          {"type": "rich_text_quote", "elements": [{"type": "text", "text": "it worked yesterday"}]},
                          {"type": "rich_text_list", "style": "ordered", "indent": 0, "elements": [
                            {"type": "rich_text_section", "elements": [{"type": "text", "text": "retry"}]},
                            {"type": "rich_text_section", "elements": [{"type": "broadcast", "range": "here"}]}
                          ]}
                        ]}]
                      }
                    }
                    """.trimIndent(),
                    Map::class.java,
                )

            `when`("the callback is parsed and mapped") {
                val mention =
                    jsonMapper
                        .convertValue(payload, SlackEventCallBackRequest::class.java)
                        .toMentionInboundCommand(appId = TEST_APP_ID, channelName = "", actorName = "")
                        .payload
                        .shouldBeInstanceOf<MentionInvocation>()

                then("the whole message is restored in order, the bot's own mention dropped") {
                    mention.text shouldBe
                        "ask why does `deploy` fail? see run 42 (https://ci.example/run/42) and " +
                        "https://ci.example/log, cc <@U_ALICE> in <#C_OPS> :fire:\n" +
                        "```\nError: exit 1\nat step build\n```\n" +
                        "> it worked yesterday\n" +
                        "1. retry\n" +
                        "2. @here"
                }
                then("command tokens and mentioned users are read as before") {
                    mention.commandTokens.take(n = 3) shouldBe listOf("ask", "why", "does")
                    mention.mentionedUserIds shouldBe listOf("U_ALICE")
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
