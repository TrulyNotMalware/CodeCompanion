package dev.notypie.application.service.meeting

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the pre-meeting reminder DM that the scheduler sends to each attending participant.
 *
 * The DM is a regular `chat.postMessage` (not `views.open`) — a scheduler tick has no
 * `trigger_id`, so no modal can be opened. The message is plain text: the meeting title, how
 * many minutes remain until it starts, and the start time. No interactive buttons are required.
 *
 * The participant's user_id is carried as [CommandBasicInfo.channel] (Slack accepts a user_id as
 * the DM channel target), mirroring how [dev.notypie.application.service.standup.StandupSchedulingService]
 * routes its standup-prompt DMs.
 */
class MeetingReminderMessageBuilder(
    private val slackEventBuilder: SlackApiEventConstructor,
) {
    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    fun buildReminderDm(
        meetingTitle: String,
        offsetMinutes: Int,
        startAt: LocalDateTime,
        commandBasicInfo: CommandBasicInfo,
    ): SendSlackMessageEvent =
        slackEventBuilder.simpleTextRequest(
            commandDetailType = CommandDetailType.MEETING_REMINDER,
            headLineText = "Meeting reminder — $meetingTitle",
            commandBasicInfo = commandBasicInfo,
            simpleString =
                "Your meeting *$meetingTitle* starts in $offsetMinutes minutes " +
                    "(at ${startAt.format(TIME_FORMAT)}).",
        )
}
