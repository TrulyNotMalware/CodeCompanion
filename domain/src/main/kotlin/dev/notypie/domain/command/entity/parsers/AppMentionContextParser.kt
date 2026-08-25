package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.entity.CommandSet
import dev.notypie.domain.command.entity.context.AgentChatContext
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.CveOpsContext
import dev.notypie.domain.command.entity.context.NoticeContext
import dev.notypie.domain.command.entity.context.RoleManagementContext
import dev.notypie.domain.command.entity.context.StatusContext
import dev.notypie.domain.command.entity.context.TextResponseContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.MentionInvocation
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.*

internal class AppMentionContextParser(
    private val commandData: InboundCommand,
    private val mention: MentionInvocation,
    val idempotencyKey: UUID,
    private val intents: IntentQueue,
    private val actorRole: UserRole,
) : ContextParser {
    companion object {
        // Markdown body for `@bot help`. Listed once here so the parser test can pin the
        // exact wording — drift between the docs and the runtime help message is the most
        // common bug we see when help text gets edited in passing.
        internal val HELP_MESSAGE: String =
            """
            *CodeCompanion — quick reference*

            *Slash commands*
            • `/meetup` — open the new-meeting form
            • `/meetup list` — show your upcoming meetings (host-owned rows have an inline *Cancel* button)
            • `/meetup list today|tomorrow|week|month` — filter by window

            *Mentions*
            • `@CodeCompanion notice @user1 @user2 <message>` — send a notice
            • `@CodeCompanion approval` — open the request-approval form
            • `@CodeCompanion help` — show this help
            • `@CodeCompanion status` — show outbox lag and in-flight counts
            • `@CodeCompanion ask <question>` — ask the AI assistant (replies in a thread; mention again in the thread to continue)
            • `@CodeCompanion grant @user <user|ai_user|developer|admin>` — grant a role (admin only)
            • `@CodeCompanion revoke @user` — remove a role grant (admin only)
            • `@CodeCompanion roles` — list all role grants (admin only)
            • `@CodeCompanion cve topics` — list CVE topics (admin only)
            • `@CodeCompanion cve topic activate|deactivate <topic-key>` — flip a topic (admin only)
            • `@CodeCompanion cve retry all|<event-id>` — re-queue dead-letter summaries (admin only)

            Anything that isn't a command above is treated as `ask`.
            `status`, `notice` and `ask` require a granted role — ask an admin if you need access.
            """.trimIndent()

        internal const val GRANT_USAGE: String =
            "Usage: `@CodeCompanion grant @user <user|ai_user|developer|admin>` — mention exactly one user and one role."

        internal const val REVOKE_USAGE: String =
            "Usage: `@CodeCompanion revoke @user` — mention exactly one user."

        internal const val ROLES_USAGE: String = "Usage: `@CodeCompanion roles` — no arguments."

        internal const val CVE_USAGE: String =
            "Usage: `@CodeCompanion cve topics` · `cve topic activate|deactivate <topic-key>` · " +
                "`cve retry all|<event-id>`."
    }

    override fun parseContext(idempotencyKey: UUID): CommandContext<NoSubCommands> {
        if (!mention.hasCommandStructure) return handleNotSupportedCommand()
        if (mention.commandTokens.isEmpty()) throw IllegalArgumentException("Command Queue is empty")
        val command: String = mention.commandTokens.first().replace(" ", "")
        val commandSet = CommandSet.parseCommand(command)
        if (!actorRole.grants(commandSet.requiredPermission)) return permissionDeniedContext(commandSet = commandSet)
        return when (commandSet) {
            CommandSet.NOTICE -> {
                NoticeContext(
                    users = LinkedList(mention.mentionedUserIds),
                    commands = LinkedList(mention.commandTokens.drop(1)),
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.APPROVAL -> {
                ApprovalFormContext(
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.HELP -> {
                TextResponseContext(
                    text = HELP_MESSAGE,
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.STATUS -> {
                StatusContext(
                    commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
                    intents = intents,
                )
            }

            CommandSet.ASK -> agentChatContext(promptTokens = mention.commandTokens.drop(1))

            CommandSet.GRANT -> grantRoleContext()

            CommandSet.REVOKE -> revokeRoleContext()

            CommandSet.ROLES ->
                if (mention.commandTokens.size == 1) {
                    roleManagementContext(intent = CommandIntent.ListRoles)
                } else {
                    usageContext(usage = ROLES_USAGE)
                }

            CommandSet.CVE -> cveOpsContext()

            // Free-text fallback: any mention that doesn't match a command is a question for the
            // AI assistant, keyword included ("what does status mean" must not lose "what").
            CommandSet.UNKNOWN -> agentChatContext(promptTokens = mention.commandTokens)
        }
    }

    // `cve` sub-dispatch. The feature gate lives in the application listener (it owns cve.enabled),
    // so an admin whose feature is off still routes here and gets a "disabled" reply, never silence.
    private fun cveOpsContext(): CommandContext<NoSubCommands> {
        val tokens = mention.commandTokens
        return when {
            tokens.size == 2 && tokens[1] == "topics" -> cveContext(intent = CommandIntent.CveListTopics)
            // Topic keys are lowercase by convention; normalizing here makes the toggle forgiving of
            // `cve topic activate Kotlin` without touching the stored keys.
            tokens.size == 4 && tokens[1] == "topic" && tokens[2] == "activate" ->
                cveContext(intent = CommandIntent.CveSetTopicActive(topicKey = tokens[3].lowercase(), active = true))
            tokens.size == 4 && tokens[1] == "topic" && tokens[2] == "deactivate" ->
                cveContext(intent = CommandIntent.CveSetTopicActive(topicKey = tokens[3].lowercase(), active = false))
            tokens.size == 3 && tokens[1] == "retry" && tokens[2] == "all" ->
                cveContext(intent = CommandIntent.CveRetryDeadLetters)
            tokens.size == 3 && tokens[1] == "retry" -> {
                val eventId = tokens[2].toLongOrNull() ?: return usageContext(usage = CVE_USAGE)
                cveContext(intent = CommandIntent.CveRetryDeadLetter(eventId = eventId))
            }
            else -> usageContext(usage = CVE_USAGE)
        }
    }

    private fun cveContext(intent: CommandIntent): CveOpsContext =
        CveOpsContext(
            intent = intent,
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun grantRoleContext(): CommandContext<NoSubCommands> {
        val targetUserId = mention.mentionedUserIds.singleOrNull() ?: return usageContext(usage = GRANT_USAGE)
        if (mention.commandTokens.size != 2) return usageContext(usage = GRANT_USAGE)
        val role =
            runCatching { UserRole.valueOf(mention.commandTokens[1].uppercase()) }.getOrNull()
                ?: return usageContext(usage = GRANT_USAGE)
        return roleManagementContext(intent = CommandIntent.GrantRole(targetUserId = targetUserId, role = role))
    }

    private fun revokeRoleContext(): CommandContext<NoSubCommands> {
        val targetUserId = mention.mentionedUserIds.singleOrNull() ?: return usageContext(usage = REVOKE_USAGE)
        if (mention.commandTokens.size != 1) return usageContext(usage = REVOKE_USAGE)
        return roleManagementContext(intent = CommandIntent.RevokeRole(targetUserId = targetUserId))
    }

    private fun roleManagementContext(intent: CommandIntent): RoleManagementContext =
        RoleManagementContext(
            intent = intent,
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun usageContext(usage: String): TextResponseContext =
        TextResponseContext(
            text = usage,
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun agentChatContext(promptTokens: List<String>): AgentChatContext =
        AgentChatContext(
            prompt = promptTokens.joinToString(separator = " "),
            // A top-level mention anchors its own thread; a threaded mention continues that thread.
            threadId = (mention.thread ?: mention.message)?.raw,
            requesterName = commandData.actorName,
            channelName = commandData.channelName,
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun handleNotSupportedCommand(): TextResponseContext =
        TextResponseContext(
            text = "Command Not supported.",
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )

    private fun permissionDeniedContext(commandSet: CommandSet): TextResponseContext {
        val target =
            if (commandSet.requiredPermission == CommandPermission.AI) {
                "the AI assistant"
            } else {
                "`${commandSet.name.lowercase()}`"
            }
        return TextResponseContext(
            text = "You don't have permission to use $target. Ask an admin to grant you access.",
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            intents = intents,
        )
    }
}
