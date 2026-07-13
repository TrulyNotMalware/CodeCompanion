package dev.notypie.application.mcp

import dev.notypie.application.service.command.RoleManagementService
import dev.notypie.application.service.ops.OpsStatusService
import dev.notypie.domain.command.authorization.CommandPermission
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.repository.meeting.MeetingRepository
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val DEFAULT_DAYS_AHEAD = 7
private val MEETING_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Read-only domain tools for the agent lane. Identity always comes from the verified turn
 * token — tools never accept user ids as arguments (a model could invent them), and every
 * dispatch goes through [McpToolGate] for role checks and auditing.
 */
class DomainReadTools(
    private val mcpToolGate: McpToolGate,
    private val opsStatusService: OpsStatusService,
    private val roleManagementService: RoleManagementService,
    private val meetingRepository: MeetingRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
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

    private fun renderMeetings(meetings: List<MeetingDto>, window: Int): String {
        val active = meetings.filterNot { it.isCanceled }
        if (active.isEmpty()) return "No meetings in the next $window day(s)."
        return active
            .sortedBy { it.startAt }
            .joinToString(separator = "\n") { meeting ->
                "• ${meeting.title} — ${meeting.startAt.format(MEETING_TIME_FORMAT)}" +
                    " (host <@${meeting.creator}>, ${meeting.participants.size} participant(s))"
            }
    }
}
