package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.entity.CommandSet
import dev.notypie.domain.command.entity.context.AgentChatContext
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.IntentContext
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
        internal val HELP_MESSAGE: String =
            """
            *CodeCompanion — quick reference*

            *Slash commands*
            • `/meetup` — open the new-meeting form
            • `/meetup list` — show your upcoming meetings (host-owned rows have *Reschedule*, *Add participant* and *Cancel* buttons)
            • `/meetup list today|tomorrow|week|month` — filter by window
            • `/calendar connect|disconnect|status` — mirror the meetings you host or accept into your own Google Calendar, unlink it, or check the link
            • `/standup setup` — create a standup routine (modal)
            • `/standup list` — show this channel's active standup routines
            • `/standup stop <routine-name>` — stop a routine (creator or admin)
            • `/subscribe` / `/unsubscribe` — pick CVE topics to follow (modal; replies by DM)
            • `/subscriptions` — list your CVE subscriptions (DM)
            • `/latest [topic-key]` — latest summarized CVE updates (DM)

            *Mentions*
            • `@CodeCompanion notice @user1 @user2 <message>` — send a notice
            • `@CodeCompanion help` — show this help
            • `@CodeCompanion status` — show outbox lag and in-flight counts
            • `@CodeCompanion usage [days]` — AI turn and tool-call usage for the last N days (default ${CommandIntent.AgentUsageReport.DEFAULT_DAYS})
            • `@CodeCompanion ask <question>` — ask the AI assistant (replies in a thread; mention again in the thread to continue)
            • `@CodeCompanion grant @user <user|ai_user|developer|admin>` — grant a role (admin only)
            • `@CodeCompanion revoke @user` — remove a role grant (admin only)
            • `@CodeCompanion roles` — list all role grants (admin only)
            • `@CodeCompanion cve topics` — list CVE topics (admin only)
            • `@CodeCompanion cve topic activate|deactivate <topic-key>` — flip a topic (admin only)
            • `@CodeCompanion cve retry all|<event-id>` — re-queue dead-letter summaries (admin only)

            Anything that isn't a command above is treated as `ask`.
            `status`, `usage`, `notice` and `ask` require a granted role — ask an admin if you need access.
            Replies to mention commands are visible only to you; `ask` answers and `notice` messages are posted to the channel.
            """.trimIndent()

        internal const val GRANT_USAGE: String =
            "Usage: `@CodeCompanion grant @user <user|ai_user|developer|admin>` — mention exactly one user and one role."

        internal const val REVOKE_USAGE: String =
            "Usage: `@CodeCompanion revoke @user` — mention exactly one user."

        internal const val ROLES_USAGE: String = "Usage: `@CodeCompanion roles` — no arguments."

        internal const val CVE_USAGE: String =
            "Usage: `@CodeCompanion cve topics` · `cve topic activate|deactivate <topic-key>` · " +
                "`cve retry all|<event-id>`."

        internal const val USAGE_USAGE: String =
            "Usage: `@CodeCompanion usage [days]` — days between 1 and ${CommandIntent.AgentUsageReport.MAX_DAYS}, " +
                "default ${CommandIntent.AgentUsageReport.DEFAULT_DAYS}."
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

            CommandSet.USAGE -> usageReportContext()

            CommandSet.ASK -> agentChatContext(prompt = agentPrompt(dropCommandWord = true))

            CommandSet.GRANT -> grantRoleContext()

            CommandSet.REVOKE -> revokeRoleContext()

            CommandSet.ROLES ->
                if (mention.commandTokens.size == 1) {
                    roleManagementContext(intent = CommandIntent.ListRoles)
                } else {
                    usageContext(usage = ROLES_USAGE)
                }

            CommandSet.CVE -> cveOpsContext()

            CommandSet.UNKNOWN -> agentChatContext(prompt = agentPrompt(dropCommandWord = false))
        }
    }

    private fun cveOpsContext(): CommandContext<NoSubCommands> {
        val tokens = mention.commandTokens
        return when {
            tokens.size == 2 && tokens[1] == "topics" -> intentContext(intent = CommandIntent.CveListTopics)
            tokens.size == 4 && tokens[1] == "topic" && tokens[2] == "activate" ->
                intentContext(
                    intent = CommandIntent.CveSetTopicActive(topicKey = tokens[3].lowercase(), active = true),
                )
            tokens.size == 4 && tokens[1] == "topic" && tokens[2] == "deactivate" ->
                intentContext(
                    intent = CommandIntent.CveSetTopicActive(topicKey = tokens[3].lowercase(), active = false),
                )
            tokens.size == 3 && tokens[1] == "retry" && tokens[2] == "all" ->
                intentContext(intent = CommandIntent.CveRetryDeadLetters)
            tokens.size == 3 && tokens[1] == "retry" -> {
                val eventId = tokens[2].toLongOrNull() ?: return usageContext(usage = CVE_USAGE)
                intentContext(intent = CommandIntent.CveRetryDeadLetter(eventId = eventId))
            }
            else -> usageContext(usage = CVE_USAGE)
        }
    }

    private fun usageReportContext(): CommandContext<NoSubCommands> {
        val tokens = mention.commandTokens
        val days =
            when (tokens.size) {
                1 -> CommandIntent.AgentUsageReport.DEFAULT_DAYS
                2 -> tokens[1].toIntOrNull()
                else -> null
            }
        if (days == null || days !in CommandIntent.AgentUsageReport.DAYS_RANGE) return usageContext(usage = USAGE_USAGE)
        return intentContext(intent = CommandIntent.AgentUsageReport(days = days))
    }

    private fun intentContext(intent: CommandIntent): IntentContext =
        IntentContext(
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

    private fun agentPrompt(dropCommandWord: Boolean): String {
        val text = mention.text.trim()
        if (text.isEmpty()) {
            val tokens = if (dropCommandWord) mention.commandTokens.drop(1) else mention.commandTokens
            return tokens.joinToString(separator = " ")
        }
        if (!dropCommandWord) return text
        val commandWord = Regex("(?<!\\S)${Regex.escape(mention.commandTokens.first())}(?!\\S)[ \\t]*")
        return commandWord.replaceFirst(input = text, replacement = "").trim()
    }

    private fun agentChatContext(prompt: String): AgentChatContext =
        AgentChatContext(
            prompt = prompt,
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
