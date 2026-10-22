package dev.notypie.domain.command.entity

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createAgentQuestionMessage
import dev.notypie.domain.command.createSlashInboundCommand
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.slash.ASK_USAGE
import dev.notypie.domain.command.entity.slash.AskCommand
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.outbound.askRefusedNotice
import dev.notypie.domain.command.outbound.directFallbackOf
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class AskCommandTest :
    BehaviorSpec({
        val denial = "You don't have permission to use the AI assistant. Ask an admin to grant you access."
        val usage = "Usage: `/ask &lt;question&gt;`"

        fun run(text: String, actorRole: UserRole): Pair<CommandOutput, List<CommandEffect>> {
            val command =
                AskCommand(
                    idempotencyKey = UUID.randomUUID(),
                    commandData = createSlashInboundCommand(subCommands = text.split(" ")),
                    actorRole = actorRole,
                )
            return command.handleEvent() to command.drainIntents()
        }

        fun CommandEffect.questionPrompt(): String =
            shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                .content
                .shouldBeInstanceOf<MessageContent.AgentQuestion>()
                .prompt

        val channel = ConversationTarget(id = TEST_CHANNEL_ID)
        val requester = UserRef(id = TEST_USER_ID)

        fun List<CommandEffect>.ephemeral(): OutboundMessage.Ephemeral =
            single().shouldBeInstanceOf<OutboundMessage.Ephemeral>().also { ephemeral ->
                ephemeral.fallback shouldBe
                    directFallbackOf(
                        channel = channel,
                        recipient = requester,
                        content = ephemeral.content.shouldBeInstanceOf<MessageContent.Text>(),
                    )
            }

        given("the /ask usage line") {
            then("names the command and its single free-text argument") {
                ASK_USAGE shouldBe "/ask <question>"
            }
        }

        given("/ask from a member who may use the AI assistant") {
            `when`("an AI_USER asks a question") {
                val (result, effects) = run(text = "what is kotlin?", actorRole = UserRole.AI_USER)

                then("it succeeds and posts the question to the command channel, unthreaded") {
                    result.ok shouldBe true
                    result.commandDetailType shouldBe CommandDetailType.AGENT_ASK_QUESTION
                    effects.single() shouldBe
                        createAgentQuestionMessage(
                            prompt = "what is kotlin?",
                            fallback =
                                askRefusedNotice(channel = channel, recipient = requester, prompt = "what is kotlin?"),
                        )
                }

                then("the fallback for a refused post tells the requester it went unanswered and quotes the question") {
                    val notice =
                        effects
                            .single()
                            .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                            .fallback
                            .shouldNotBeNull()
                    notice.recipient shouldBe requester
                    notice.content.markdown shouldContain "so it was *not* answered"
                    notice.content.markdown shouldEndWith "\n\n> what is kotlin?"
                }
            }

            `when`("the question has runs of spaces inside and blanks around it") {
                val (_, effects) = run(text = "  why  is   the build slow? ", actorRole = UserRole.AI_USER)

                then("the prompt keeps the inner spacing exactly and drops the outer blanks") {
                    effects.single().questionPrompt() shouldBe "why  is   the build slow?"
                }
            }

            `when`("an ADMIN asks a question that spans two lines") {
                val (result, effects) = run(text = "first line\nsecond line", actorRole = UserRole.ADMIN)

                then("the gate lets ADMIN through and the line break survives") {
                    result.ok shouldBe true
                    effects.single().questionPrompt() shouldBe "first line\nsecond line"
                }
            }
        }

        given("/ask from a member without the AI permission") {
            `when`("a USER asks a question") {
                val (result, effects) = run(text = "what is kotlin?", actorRole = UserRole.USER)

                then("it succeeds with a requester-only denial and posts nothing to the channel") {
                    result.ok shouldBe true
                    val ephemeral = effects.ephemeral()
                    ephemeral.recipient shouldBe UserRef(id = TEST_USER_ID)
                    ephemeral.content shouldBe MessageContent.Text(headline = null, markdown = denial)
                }
            }

            `when`("a USER sends no question") {
                val (_, effects) = run(text = "", actorRole = UserRole.USER)

                then("the denial wins over the usage hint") {
                    effects.ephemeral().content shouldBe MessageContent.Text(headline = null, markdown = denial)
                }
            }
        }

        given("/ask without a question from an AI_USER") {
            `when`("the text is empty") {
                val (result, effects) = run(text = "", actorRole = UserRole.AI_USER)

                then("it fails with the escaped usage line to the requester and posts nothing to the channel") {
                    result.ok shouldBe false
                    val ephemeral = effects.ephemeral()
                    ephemeral.recipient shouldBe requester
                    ephemeral.content shouldBe MessageContent.Text(headline = null, markdown = usage)
                }
            }

            `when`("the text is only spaces") {
                val (result, effects) = run(text = "   ", actorRole = UserRole.AI_USER)

                then("it fails with the same usage line") {
                    result.ok shouldBe false
                    effects.ephemeral().content shouldBe MessageContent.Text(headline = null, markdown = usage)
                }
            }

            `when`("the text is only a NEL (U+0085)") {
                val (result, effects) = run(text = "\u0085", actorRole = UserRole.AI_USER)

                then("it fails with the same usage line instead of posting an empty question") {
                    result.ok shouldBe false
                    effects.ephemeral().content shouldBe MessageContent.Text(headline = null, markdown = usage)
                }
            }

            `when`("the text is only zero-width characters (U+200B, U+FEFF)") {
                val (result, effects) = run(text = "\u200B\uFEFF", actorRole = UserRole.AI_USER)

                then("it fails with the same usage line instead of posting an empty question") {
                    result.ok shouldBe false
                    effects.ephemeral().content shouldBe MessageContent.Text(headline = null, markdown = usage)
                }
            }
        }
    })
