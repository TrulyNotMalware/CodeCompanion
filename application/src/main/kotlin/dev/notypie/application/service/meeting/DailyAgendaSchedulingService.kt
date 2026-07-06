package dev.notypie.application.service.meeting

import dev.notypie.application.common.runInTx
import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.meeting.AgendaCandidateMeeting
import dev.notypie.repository.meeting.AgendaDispatchRepository
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

private val AGENDA_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Sends each user a once-per-day DM listing the meetings they attend today. Two guards keep it
 * correct under repeated ticks/restarts: a time-of-day gate (the configured send time in [zone])
 * and an atomic per-date `INSERT IGNORE` claim so the agenda is built at most once.
 */
@Service
class DailyAgendaSchedulingService(
    private val agendaDispatchRepository: AgendaDispatchRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val enabled: Boolean = appConfig.meeting.agenda.enabled
    private val sendTime: LocalTime = LocalTime.parse(appConfig.meeting.agenda.sendAt)
    private val zone: ZoneId = ZoneId.of(appConfig.meeting.agenda.timezone)

    fun sendDailyAgenda() {
        if (!enabled) return

        val now = clock.instant()
        val today = LocalDate.ofInstant(now, zone)
        val localTime = now.atZone(zone).toLocalTime()

        // Don't fire before the configured morning send time.
        if (localTime.isBefore(sendTime)) return

        // Atomic once-per-day claim — a tick that already claimed today stands down.
        if (!agendaDispatchRepository.claim(agendaDate = today)) return

        val dayStart = today.atStartOfDay()
        val nextDayStart = today.plusDays(1L).atStartOfDay()
        val meetings = agendaDispatchRepository.findAttendingMeetingsForDay(from = dayStart, to = nextDayStart)

        val agendaByUser = groupByAttendingUser(meetings = meetings)
        if (agendaByUser.isEmpty()) {
            log.info { "Daily agenda for $today claimed but no attending meetings — no DMs sent" }
            return
        }

        val outcome =
            transactionTemplate.runInTx {
                agendaByUser.forEach { (userId, items) ->
                    val commandBasicInfo =
                        CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                    val message =
                        buildAgendaDm(
                            agendaDate = today,
                            meetings = items,
                            commandBasicInfo = commandBasicInfo,
                        )
                    outboxRepository.save(
                        outboundMessagePort.toRow(message = message, basicInfo = commandBasicInfo),
                    )
                }
            }

        if (outcome.isFailure) {
            log.error(outcome.exceptionOrNull()) { "Daily agenda dispatch failed for $today" }
        } else {
            val meetingCount = agendaByUser.values.sumOf { it.size }
            log.info { "Daily agenda enqueued for $today: users=${agendaByUser.size} meetings=$meetingCount" }
        }
    }

    /** Fans each meeting out to its attending participants, one [AgendaItem] list per user. */
    private fun groupByAttendingUser(meetings: List<AgendaCandidateMeeting>): Map<String, List<AgendaItem>> {
        val byUser = mutableMapOf<String, MutableList<AgendaItem>>()
        meetings.forEach { meeting ->
            val item = AgendaItem(startAt = meeting.startAt, title = meeting.title)
            meeting.attendingUserIds.forEach { userId ->
                byUser.getOrPut(userId) { mutableListOf() }.add(item)
            }
        }
        return byUser
    }
}

/**
 * Builds the morning daily-agenda DM: a date header followed by one line per meeting, sorted by
 * start time. A scheduler tick has no `trigger_id`, so this is a plain `chat.postMessage`.
 */
internal fun buildAgendaDm(
    agendaDate: LocalDate,
    meetings: List<AgendaItem>,
    commandBasicInfo: CommandBasicInfo,
): OutboundMessage.ChannelMessage {
    val header = "🗓️ Today's meetings ($agendaDate)"
    val lines =
        meetings
            .sortedBy { it.startAt }
            .joinToString(separator = "\n") { item ->
                "• ${item.startAt.format(AGENDA_TIME_FORMAT)} — ${item.title}"
            }
    return OutboundMessage.ChannelMessage(
        target = ConversationTarget(id = commandBasicInfo.channel),
        content = MessageContent.Text(headline = header, markdown = lines),
        detailType = CommandDetailType.DAILY_AGENDA,
    )
}
