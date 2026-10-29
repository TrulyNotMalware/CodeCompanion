package dev.notypie.domain.command.parsers

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createMentionInboundCommand
import dev.notypie.domain.command.entity.CommandSet
import dev.notypie.domain.command.entity.context.TextResponseContext
import dev.notypie.domain.command.entity.parsers.AppMentionContextParser
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class MentionPermissionMatrixTest :
    BehaviorSpec({
        val idempotencyKey = UUID.randomUUID()

        val lowestRoleByKeyword: Map<CommandSet, UserRole> =
            mapOf(
                CommandSet.HELP to UserRole.USER,
                CommandSet.ASK to UserRole.AI_USER,
                CommandSet.UNKNOWN to UserRole.AI_USER,
                CommandSet.NOTICE to UserRole.DEVELOPER,
                CommandSet.STATUS to UserRole.DEVELOPER,
                CommandSet.USAGE to UserRole.DEVELOPER,
                CommandSet.GRANT to UserRole.ADMIN,
                CommandSet.REVOKE to UserRole.ADMIN,
                CommandSet.ROLES to UserRole.ADMIN,
                CommandSet.CVE to UserRole.ADMIN,
            )

        val tokensByKeyword: Map<CommandSet, List<String>> =
            mapOf(
                CommandSet.UNKNOWN to listOf("what", "is", "up"),
                CommandSet.NOTICE to listOf("notice", "hi"),
            )

        fun isDenied(commandSet: CommandSet, role: UserRole): Boolean {
            val queue = createIntentQueue()
            val result =
                AppMentionContextParser(
                    commandData = createMentionInboundCommand(),
                    mention =
                        MentionInvocation(
                            mentionedUserIds = emptyList(),
                            commandTokens = tokensByKeyword[commandSet] ?: listOf(commandSet.name.lowercase()),
                            hasCommandStructure = true,
                            message = null,
                            thread = null,
                            text = "",
                        ),
                    idempotencyKey = idempotencyKey,
                    intents = queue,
                    actorRole = role,
                ).parseContext(idempotencyKey = idempotencyKey)
            if (result !is TextResponseContext) return false
            result.runCommand()
            val reply = queue.snapshot().first().shouldBeInstanceOf<OutboundMessage.Ephemeral>()
            return reply.content
                .shouldBeInstanceOf<MessageContent.Text>()
                .markdown
                .startsWith(prefix = "You don't have permission to use")
        }

        given("the mention permission matrix written from the README role table and the help text") {
            `when`("the expectation table is compared with the command set") {
                then("every command keyword has an expectation") {
                    lowestRoleByKeyword.keys shouldBe CommandSet.entries.toSet()
                }
            }

            CommandSet.entries.forEach { commandSet ->
                UserRole.entries.forEach { role ->
                    val expectedDenied = role < lowestRoleByKeyword.getValue(key = commandSet)
                    `when`("${role.name} runs ${commandSet.name.lowercase()}") {
                        then(if (expectedDenied) "the command is denied" else "the command is not denied") {
                            isDenied(commandSet = commandSet, role = role) shouldBe expectedDenied
                        }
                    }
                }
            }
        }
    })
