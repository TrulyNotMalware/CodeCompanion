package dev.notypie.application.service.meeting

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Builds the morning daily-agenda DM that the scheduler sends to each user with meetings today.
 *
 * The DM is a regular `chat.postMessage` (not `views.open`) — a scheduler tick has no
 * `trigger_id`, so no modal can be opened. The message is plain text: a date header followed by
 * one line per meeting sorted by start time. No interactive buttons are required.
 *
 * The user's user_id is carried as [CommandBasicInfo.channel] (Slack accepts a user_id as the DM
 * channel target), mirroring [MeetingReminderMessageBuilder].
 */
class DailyAgendaMessageBuilder(
    private val slackEventBuilder: SlackApiEventConstructor,
) {
    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun buildAgendaDm(
        userId: String,
        agendaDate: LocalDate,
        meetings: List<AgendaItem>,
        commandBasicInfo: CommandBasicInfo,
    ): SendSlackMessageEvent {
        val header = "🗓️ Today's meetings ($agendaDate)"
        val lines =
            meetings
                .sortedBy { it.startAt }
                .joinToString(separator = "\n") { item ->
                    "• ${item.startAt.format(TIME_FORMAT)} — ${item.title}"
                }
        return slackEventBuilder.simpleTextRequest(
            commandDetailType = CommandDetailType.DAILY_AGENDA,
            headLineText = header,
            commandBasicInfo = commandBasicInfo,
            simpleString = lines,
        )
    }
}
