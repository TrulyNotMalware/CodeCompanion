package dev.notypie.application.service.standup

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Builds the standup-prompt DM that the scheduler sends to each participant.
 *
 * The DM is a regular `chat.postMessage` (not `views.open`) — Slack requires a `trigger_id`
 * from a user interaction to open a modal, and a scheduler tick has none. The message
 * contains a "Fill in standup" button; clicking it produces the trigger_id that the
 * follow-up [dev.notypie.domain.command.entity.context.form.StandupFillContext] uses to
 * open the modal in Phase 3 #11.
 */
class StandupDispatchMessageBuilder(
    private val slackEventBuilder: SlackApiEventConstructor,
) {
    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val CUTOFF_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun buildDmNotice(
        sessionUid: UUID,
        sessionDate: LocalDate,
        routineUid: UUID,
        routineName: String,
        memberId: String,
        commandBasicInfo: CommandBasicInfo,
    ): SendSlackMessageEvent {
        val approvalContents =
            ApprovalContents(
                headLineText = "$routineName — ${sessionDate.format(DATE_FORMAT)}",
                reason = routineName,
                publisherId = commandBasicInfo.publisherId,
                approvalButtonName = "Fill in standup",
                rejectButtonName = "Skip",
                idempotencyKey = sessionUid,
                commandDetailType = CommandDetailType.STANDUP_FILL,
            )
        return slackEventBuilder.simpleApplyRejectRequest(
            commandDetailType = CommandDetailType.STANDUP_FILL,
            commandBasicInfo = commandBasicInfo,
            approvalContents = approvalContents,
            targetUserId = memberId,
            routingExtras = listOf(sessionUid.toString(), routineUid.toString()),
        )
    }

    /**
     * Builds the once-per-session non-responder reminder DM. Unlike [buildDmNotice] this is a
     * plain `chat.postMessage` with no buttons — it nudges the member back to the original
     * prompt's "Fill in standup" button rather than re-issuing an interactive flow. The DM is
     * routed to the member via [commandBasicInfo]'s channel (Slack treats a user_id as the DM
     * channel), set by the caller through `CommandBasicInfo.forOutbound`.
     */
    fun buildNudgeNotice(
        routineName: String,
        cutoffAt: Instant,
        routineTimezone: ZoneId,
        commandBasicInfo: CommandBasicInfo,
    ): SendSlackMessageEvent {
        val cutoffText = CUTOFF_TIME_FORMAT.format(cutoffAt.atZone(routineTimezone))
        val body =
            "⏰ Standup for *$routineName* closes at $cutoffText — you haven't responded yet. " +
                "Tap the *Fill in standup* button in your DM."
        return slackEventBuilder.simpleTextRequest(
            commandDetailType = CommandDetailType.STANDUP_FILL,
            headLineText = "Standup reminder",
            commandBasicInfo = commandBasicInfo,
            simpleString = body,
        )
    }
}
