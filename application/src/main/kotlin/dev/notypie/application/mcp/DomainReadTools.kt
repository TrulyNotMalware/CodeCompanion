package dev.notypie.application.mcp

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.cve.ops.CVE_FEATURE_DISABLED_MESSAGE
import dev.notypie.application.service.cve.query.CveLatestQueryService
import dev.notypie.application.service.cve.subscription.CveSubscriptionService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.common.escapeMarkup
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.standup.dto.RoutineDto
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.standup.StandupRepository
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val DEFAULT_DAYS_AHEAD = 7
private val MEETING_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val ROUTINE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

class DomainReadTools(
    private val mcpToolGate: McpToolGate,
    private val opsStatusService: OpsStatusService,
    private val roleManagementService: RoleManagementService,
    private val meetingRepository: MeetingRepository,
    private val standupRepository: StandupRepository,
    private val cveSubscriptionService: CveSubscriptionService,
    private val cveLatestQueryService: CveLatestQueryService,
    private val appConfig: AppConfig,
    private val clock: Clock,
) {
    @McpTool(
        name = "get_status",
        description = "Show the message-outbox status: pending/in-flight counts, lag, and overall health.",
    )
    fun getStatus(context: McpSyncRequestContext): CallToolResult =
        mcpToolGate.execute(
            transportContext = context.transportContext(),
            toolName = "get_status",
            requiredPermission = CommandPermission.OPERATIONS,
        ) { opsStatusService.renderReport() }

    @McpTool(
        name = "list_meetings",
        description = "List the requesting user's upcoming meetings inside the given window.",
    )
    fun listMeetings(
        @McpToolParam(
            description = "How many days ahead to include, 1..31. Defaults to 7.",
            required = false,
        ) daysAhead: Int?,
        context: McpSyncRequestContext,
    ): CallToolResult {
        val window = (daysAhead ?: DEFAULT_DAYS_AHEAD).coerceIn(1, 31)
        return mcpToolGate.execute(
            transportContext = context.transportContext(),
            toolName = "list_meetings",
            requiredPermission = CommandPermission.BASIC,
            argumentsSummary = """{"daysAhead":$window}""",
        ) { token ->
            val now = LocalDateTime.now(clock)
            val meetings =
                meetingRepository.getMeetingsByUserIdInRange(
                    userId = token.userId,
                    startAt = now,
                    endAt = now.plusDays(window.toLong()),
                )
            renderMeetings(meetings = meetings, window = window)
        }
    }

    @McpTool(
        name = "list_roles",
        description = "List every command-role grant, including config-managed bootstrap admins.",
    )
    fun listRoles(context: McpSyncRequestContext): CallToolResult =
        mcpToolGate.execute(
            transportContext = context.transportContext(),
            toolName = "list_roles",
            requiredPermission = CommandPermission.ADMINISTRATION,
        ) { roleManagementService.renderGrants() }

    @McpTool(
        name = "list_standups",
        description = "List the standup routines the requesting user belongs to (as creator or member).",
    )
    fun listStandups(context: McpSyncRequestContext): CallToolResult =
        mcpToolGate.execute(
            transportContext = context.transportContext(),
            toolName = "list_standups",
            requiredPermission = CommandPermission.BASIC,
        ) { token ->
            val routines =
                standupRepository.listActiveRoutines().filter { routine ->
                    routine.creatorId == token.userId || routine.members.any { it.userId == token.userId }
                }
            renderRoutines(routines = routines)
        }

    @McpTool(
        name = "list_cve_subscriptions",
        description = "List the CVE topics the requesting user is subscribed to.",
    )
    fun listCveSubscriptions(context: McpSyncRequestContext): CallToolResult =
        mcpToolGate.execute(
            transportContext = context.transportContext(),
            toolName = "list_cve_subscriptions",
            requiredPermission = CommandPermission.BASIC,
        ) { token ->
            if (appConfig.cve.enabled) {
                cveSubscriptionService.renderSubscriptions(userId = token.userId)
            } else {
                CVE_FEATURE_DISABLED_MESSAGE
            }
        }

    @McpTool(
        name = "cve_latest",
        description =
            "Show the latest summarized CVE updates for the requester's subscribed topics, " +
                "or for one topic key.",
    )
    fun cveLatest(
        @McpToolParam(
            description = "Topic key to filter by; omit to use the requester's subscriptions.",
            required = false,
        ) topicKey: String?,
        context: McpSyncRequestContext,
    ): CallToolResult {
        val key = topicKey?.takeIf { it.isNotBlank() }
        val argumentsSummary =
            if (key == null) {
                """{"topicKey":null}"""
            } else {
                """{"topicKey":"${key.replace(oldChar = '"', newChar = '\'')}"}"""
            }
        return mcpToolGate.execute(
            transportContext = context.transportContext(),
            toolName = "cve_latest",
            requiredPermission = CommandPermission.BASIC,
            argumentsSummary = argumentsSummary,
        ) { token ->
            if (appConfig.cve.enabled) {
                cveLatestQueryService.renderLatest(userId = token.userId, topicKey = key)
            } else {
                CVE_FEATURE_DISABLED_MESSAGE
            }
        }
    }

    private fun renderMeetings(meetings: List<MeetingDto>, window: Int): String {
        val active = meetings.filterNot { it.isCanceled }
        if (active.isEmpty()) return "No meetings in the next $window day(s)."
        return active
            .sortedBy { it.startAt }
            .joinToString(separator = "\n") { meeting ->
                "• ${meeting.title.escapeMarkup()} — ${meeting.startAt.format(MEETING_TIME_FORMAT)}" +
                    " (host <@${meeting.creator}>, ${meeting.participants.size} participant(s))"
            }
    }

    private fun renderRoutines(routines: List<RoutineDto>): String {
        if (routines.isEmpty()) return "You are not in any active standup routine."
        return routines.joinToString(separator = "\n") { routine ->
            val weekdays =
                routine.weekdays
                    .sortedBy { it.value }
                    .joinToString(separator = "/") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
            "• ${routine.name.escapeMarkup()} — ${routine.triggerLocalTime.format(ROUTINE_TIME_FORMAT)} " +
                "${routine.routineTimezone.id}, $weekdays, cutoff +${routine.cutoffOffset.toMinutes()}m, " +
                "${routine.members.size} member(s), channel <#${routine.commandChannel}>, " +
                "summary <#${routine.summaryChannel}>, created by <@${routine.creatorId}>"
        }
    }
}
