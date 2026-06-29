package dev.notypie.application.service.meeting

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.repository.meeting.AgendaCandidateMeeting
import dev.notypie.repository.meeting.AgendaDispatchRepository
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val log = KotlinLogging.logger {}

/**
 * Sends each user a once-per-day DM listing the meetings they are attending today.
 *
 * The scheduler ticks every minute; this service is the gate + dispatch. Two guards keep it
 * correct under repeated ticks and restarts:
 *
 *   1. **Time-of-day gate** — proceed only once the local time in [agendaZone] has passed the
 *      configured [sendAt], so the agenda fires after the configured morning time rather than at
 *      midnight when the calendar date first rolls over.
 *   2. **Per-date claim** — `agendaDispatchRepository.claim(today)` is an atomic `INSERT IGNORE`
 *      on the agenda date; only the tick that inserts the row proceeds, so the agenda for a given
 *      day is built at most once even across overlapping ticks.
 */
@Service
class DailyAgendaSchedulingService(
    private val agendaDispatchRepository: AgendaDispatchRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val messageBuilder: DailyAgendaMessageBuilder,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
    @param:Value("\${meeting.agenda.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${meeting.agenda.send-at:08:00}")
    sendAt: String = "08:00",
    @Value("\${meeting.agenda.timezone:Asia/Seoul}")
    agendaZone: String = "Asia/Seoul",
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val sendTime: LocalTime = LocalTime.parse(sendAt)

    private val zone: ZoneId = ZoneId.of(agendaZone)

    fun sendDailyAgenda() {
        if (!enabled) return

        val now = clock.instant()
        val today = LocalDate.ofInstant(now, zone)
        val localTime = now.atZone(zone).toLocalTime()

        // Time-of-day gate: don't fire before the configured morning send time.
        if (localTime.isBefore(sendTime)) return

        // Atomic once-per-day claim — a concurrent tick that already claimed today stands down.
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
                        CommandBasicInfo.forOutbound(
                            publisherId = userId,
                            // Slack accepts a user_id as the DM channel target.
                            channel = userId,
                        )
                    val dmEvent =
                        messageBuilder.buildAgendaDm(
                            userId = userId,
                            agendaDate = today,
                            meetings = items,
                            commandBasicInfo = commandBasicInfo,
                        )
                    outboxRepository.save(dmEvent.toOutboxMessage())
                }
            }

        if (outcome.isFailure) {
            log.error(outcome.exceptionOrNull()) { "Daily agenda dispatch failed for $today" }
        } else {
            val meetingCount = agendaByUser.values.sumOf { it.size }
            log.info { "Daily agenda enqueued for $today: users=${agendaByUser.size} meetings=$meetingCount" }
        }
    }

    /**
     * Fans out each meeting to its attending participants, producing one [AgendaItem] list per
     * user. Users with no attending meetings never appear, so they receive no DM.
     */
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
