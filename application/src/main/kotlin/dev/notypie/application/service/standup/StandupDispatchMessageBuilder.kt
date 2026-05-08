package dev.notypie.application.service.standup

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import java.time.LocalDate
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
}
