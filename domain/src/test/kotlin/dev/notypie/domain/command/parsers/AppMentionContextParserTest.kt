package dev.notypie.domain.command.parsers

import dev.notypie.domain.TEST_MESSAGE_TS
import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.authorization.UserRole
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
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
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

        // Routing cases below run as ADMIN so every command stays reachable; the dedicated
        // authorization block exercises the restrictive roles.
        fun createParser(
            mention: MentionInvocation,
            intentQueue: IntentQueue = intents,
            actorRole: UserRole = UserRole.ADMIN,
        ): AppMentionContextParser =
            AppMentionContextParser(
                commandData = createMentionInboundCommand(),
                mention = mention,
                idempotencyKey = idempotencyKey,
                intents = intentQueue,
                actorRole = actorRole,
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
                    intent.requesterName shouldBe TEST_USER_NAME
                    intent.channelName shouldBe "general"
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

        given("command authorization") {
            fun deniedMarkdown(tokens: List<String>, actorRole: UserRole): String {
                val denialIntents = createIntentQueue()
                val result =
                    createParser(
                        mention = mentionOf(tokens = tokens),
                        intentQueue = denialIntents,
                        actorRole = actorRole,
                    ).parseContext(idempotencyKey = idempotencyKey)
                result.shouldBeInstanceOf<TextResponseContext>()
                result.runCommand()
                return denialIntents
                    .snapshot()
                    .first()
                    .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    .content
                    .shouldBeInstanceOf<MessageContent.Text>()
                    .markdown
            }

            `when`("a USER runs `status`") {
                then("the command is denied with the command name") {
                    deniedMarkdown(tokens = listOf("status"), actorRole = UserRole.USER) shouldBe
                        "You don't have permission to use `status`. Ask an admin to grant you access."
                }
            }

            `when`("a USER runs `notice`") {
                then("the command is denied") {
                    deniedMarkdown(tokens = listOf("notice", "hi"), actorRole = UserRole.USER) shouldBe
                        "You don't have permission to use `notice`. Ask an admin to grant you access."
                }
            }

            `when`("a USER sends free text (the ask fallback)") {
                then("the AI lane is denied") {
                    deniedMarkdown(tokens = listOf("what", "is", "up"), actorRole = UserRole.USER) shouldBe
                        "You don't have permission to use the AI assistant. Ask an admin to grant you access."
                }
            }

            `when`("a USER runs `help`") {
                val result =
                    createParser(
                        mention = mentionOf(tokens = listOf("help")),
                        actorRole = UserRole.USER,
                    ).parseContext(idempotencyKey = idempotencyKey)

                then("the BASIC command still routes") {
                    result.shouldBeInstanceOf<TextResponseContext>()
                }
            }

            `when`("an AI_USER runs `ask`") {
                val result =
                    createParser(
                        mention = mentionOf(tokens = listOf("ask", "hello")),
                        intentQueue = createIntentQueue(),
                        actorRole = UserRole.AI_USER,
                    ).parseContext(idempotencyKey = idempotencyKey)

                then("the AI lane routes") {
                    result.shouldBeInstanceOf<AgentChatContext>()
                }
            }

            `when`("an AI_USER runs `status`") {
                then("the operational command is denied") {
                    deniedMarkdown(tokens = listOf("status"), actorRole = UserRole.AI_USER) shouldBe
                        "You don't have permission to use `status`. Ask an admin to grant you access."
                }
            }

            `when`("a DEVELOPER runs `grant`") {
                then("the administration command is denied") {
                    deniedMarkdown(tokens = listOf("grant", "developer"), actorRole = UserRole.DEVELOPER) shouldBe
                        "You don't have permission to use `grant`. Ask an admin to grant you access."
                }
            }

            `when`("a DEVELOPER runs `notice` and `ask`") {
                val noticeResult =
                    createParser(
                        mention = mentionOf(tokens = listOf("notice", "hi"), userIds = listOf(TEST_USER_ID)),
                        intentQueue = createIntentQueue(),
                        actorRole = UserRole.DEVELOPER,
                    ).parseContext(idempotencyKey = idempotencyKey)
                val askResult =
                    createParser(
                        mention = mentionOf(tokens = listOf("ask", "hello")),
                        intentQueue = createIntentQueue(),
                        actorRole = UserRole.DEVELOPER,
                    ).parseContext(idempotencyKey = idempotencyKey)

                then("both operational and AI commands route") {
                    noticeResult.shouldBeInstanceOf<NoticeContext>()
                    askResult.shouldBeInstanceOf<AgentChatContext>()
                }
            }
        }

        given("role management commands (as ADMIN)") {
            fun firstEffectOf(tokens: List<String>, userIds: List<String> = emptyList()): Any {
                val queue = createIntentQueue()
                createParser(
                    mention = mentionOf(tokens = tokens, userIds = userIds),
                    intentQueue = queue,
                ).parseContext(idempotencyKey = idempotencyKey).runCommand()
                return queue.snapshot().first()
            }

            `when`("`grant @user developer` is well-formed") {
                then("a GrantRole intent carries the target and parsed role") {
                    firstEffectOf(tokens = listOf("grant", "developer"), userIds = listOf(TEST_USER_ID)) shouldBe
                        CommandIntent.GrantRole(targetUserId = TEST_USER_ID, role = UserRole.DEVELOPER)
                }
            }

            `when`("`grant` has no mentioned user") {
                then("the usage text is returned instead of an intent") {
                    firstEffectOf(tokens = listOf("grant", "developer"))
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AppMentionContextParser.GRANT_USAGE
                }
            }

            `when`("`grant` carries extra trailing tokens") {
                then("the usage text is returned instead of mutating state") {
                    firstEffectOf(tokens = listOf("grant", "developer", "extra"), userIds = listOf(TEST_USER_ID))
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AppMentionContextParser.GRANT_USAGE
                }
            }

            `when`("`grant` names an unknown role") {
                then("the usage text is returned") {
                    firstEffectOf(tokens = listOf("grant", "superuser"), userIds = listOf(TEST_USER_ID))
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AppMentionContextParser.GRANT_USAGE
                }
            }

            `when`("`revoke @user` is well-formed") {
                then("a RevokeRole intent carries the target") {
                    firstEffectOf(tokens = listOf("revoke"), userIds = listOf(TEST_USER_ID)) shouldBe
                        CommandIntent.RevokeRole(targetUserId = TEST_USER_ID)
                }
            }

            `when`("`revoke` has no mentioned user") {
                then("the usage text is returned") {
                    firstEffectOf(tokens = listOf("revoke"))
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AppMentionContextParser.REVOKE_USAGE
                }
            }

            `when`("`revoke` carries extra trailing tokens") {
                then("the usage text is returned instead of mutating state") {
                    firstEffectOf(tokens = listOf("revoke", "extra"), userIds = listOf(TEST_USER_ID))
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AppMentionContextParser.REVOKE_USAGE
                }
            }

            `when`("`roles` is issued") {
                then("a ListRoles intent is emitted") {
                    firstEffectOf(tokens = listOf("roles")) shouldBe CommandIntent.ListRoles
                }
            }

            `when`("`roles` carries arguments") {
                then("the usage text is returned") {
                    firstEffectOf(tokens = listOf("roles", "extra"))
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe AppMentionContextParser.ROLES_USAGE
                }
            }
        }

        given("cve operations commands (as ADMIN)") {
            fun firstEffectOf(tokens: List<String>): Any {
                val queue = createIntentQueue()
                createParser(mention = mentionOf(tokens = tokens), intentQueue = queue)
                    .parseContext(idempotencyKey = idempotencyKey)
                    .runCommand()
                return queue.snapshot().first()
            }

            fun usageOf(tokens: List<String>): String =
                firstEffectOf(tokens = tokens)
                    .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    .content
                    .shouldBeInstanceOf<MessageContent.Text>()
                    .markdown

            `when`("`cve topics` is issued") {
                then("a CveListTopics intent is emitted") {
                    firstEffectOf(tokens = listOf("cve", "topics")) shouldBe CommandIntent.CveListTopics
                }
            }

            `when`("`cve topic activate <key>` is well-formed") {
                then("a CveSetTopicActive intent carries the key and active=true") {
                    firstEffectOf(tokens = listOf("cve", "topic", "activate", "spring")) shouldBe
                        CommandIntent.CveSetTopicActive(topicKey = "spring", active = true)
                }
            }

            `when`("`cve topic deactivate <key>` is well-formed") {
                then("a CveSetTopicActive intent carries the key and active=false") {
                    firstEffectOf(tokens = listOf("cve", "topic", "deactivate", "spring")) shouldBe
                        CommandIntent.CveSetTopicActive(topicKey = "spring", active = false)
                }
            }

            `when`("`cve retry all` is issued") {
                then("a CveRetryDeadLetters intent is emitted") {
                    firstEffectOf(tokens = listOf("cve", "retry", "all")) shouldBe CommandIntent.CveRetryDeadLetters
                }
            }

            `when`("`cve retry <event-id>` names a numeric id") {
                then("a CveRetryDeadLetter intent carries the parsed id") {
                    firstEffectOf(tokens = listOf("cve", "retry", "42")) shouldBe
                        CommandIntent.CveRetryDeadLetter(eventId = 42L)
                }
            }

            `when`("`cve retry` names a non-numeric id") {
                then("the usage text is returned instead of an intent") {
                    usageOf(tokens = listOf("cve", "retry", "nope")) shouldBe AppMentionContextParser.CVE_USAGE
                }
            }

            `when`("`cve` is issued with no sub-command") {
                then("the usage text is returned") {
                    usageOf(tokens = listOf("cve")) shouldBe AppMentionContextParser.CVE_USAGE
                }
            }

            `when`("`cve` carries an unknown sub-command") {
                then("the usage text is returned") {
                    usageOf(tokens = listOf("cve", "bogus", "thing")) shouldBe AppMentionContextParser.CVE_USAGE
                }
            }

            `when`("a DEVELOPER runs `cve topics`") {
                val denialIntents = createIntentQueue()
                val result =
                    createParser(
                        mention = mentionOf(tokens = listOf("cve", "topics")),
                        intentQueue = denialIntents,
                        actorRole = UserRole.DEVELOPER,
                    ).parseContext(idempotencyKey = idempotencyKey)

                then("the administration command is denied with the permission message") {
                    result.shouldBeInstanceOf<TextResponseContext>()
                    result.runCommand()
                    denialIntents
                        .snapshot()
                        .first()
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe "You don't have permission to use `cve`. Ask an admin to grant you access."
                }
            }

            `when`("`cve topic activate` carries an upper-cased key") {
                then("the key is normalized to the lowercase convention") {
                    firstEffectOf(tokens = listOf("cve", "topic", "activate", "Kotlin")) shouldBe
                        CommandIntent.CveSetTopicActive(topicKey = "kotlin", active = true)
                }
            }
        }
    })
