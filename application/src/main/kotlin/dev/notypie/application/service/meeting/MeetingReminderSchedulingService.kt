package dev.notypie.application.service.meeting

import dev.notypie.application.common.runInTx
import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.ReadyReminder
import dev.notypie.repository.meeting.ReminderCandidateMeeting
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val log = KotlinLogging.logger {}

private val REMINDER_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Service
class MeetingReminderSchedulingService(
    private val reminderRepository: MeetingReminderRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
    appConfig: AppConfig = AppConfig(),
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val offsetsMinutes: List<Int> = appConfig.meeting.reminder.offsetsMinutes
    private val stuckSendingThresholdMinutes: Long = appConfig.meeting.reminder.stuckSendingThresholdMinutes
    private val dispatchBatchSize: Int = appConfig.meeting.reminder.dispatchBatchSize
    private val materializeLookbackMinutes: Long = appConfig.meeting.reminder.materializeLookbackMinutes

    private val maxOffsetMinutes: Int = offsetsMinutes.maxOrNull() ?: 0

    fun materializeReminders() {
        if (maxOffsetMinutes <= 0) return
        val now = clock.instant()
        val zone = clock.zone
        val windowFrom = LocalDateTime.ofInstant(now.minus(Duration.ofMinutes(materializeLookbackMinutes)), zone)
        val windowTo = LocalDateTime.ofInstant(now.plus(Duration.ofMinutes(maxOffsetMinutes.toLong())), zone)

        reminderRepository.findActiveMeetingsInWindow(from = windowFrom, to = windowTo).forEach { meeting ->
            materializeMeeting(meeting = meeting, now = now, zone = clock.zone)
        }
    }

    private fun materializeMeeting(meeting: ReminderCandidateMeeting, now: Instant, zone: ZoneId) {
        if (meeting.attendingUserIds.isEmpty()) return
        val startInstant = meeting.startAt.atZone(zone).toInstant()

        offsetsMinutes.forEach { offsetMinutes ->
            val scheduledAt = startInstant.minus(Duration.ofMinutes(offsetMinutes.toLong()))
            if (scheduledAt.isBefore(now.minus(Duration.ofMinutes(stuckSendingThresholdMinutes)))) return@forEach

            try {
                if (reminderRepository.ensureReminder(
                        meetingId = meeting.meetingId,
                        offsetMinutes = offsetMinutes,
                        scheduledAt = scheduledAt,
                    )
                ) {
                    log.info {
                        "Meeting reminder materialized: meetingId=${meeting.meetingId} offset=$offsetMinutes"
                    }
                }
            } catch (ex: DataIntegrityViolationException) {
                if (reminderRepository.reminderExists(meetingId = meeting.meetingId, offsetMinutes = offsetMinutes)) {
                    log.debug {
                        "Reminder race lost (concurrent tick): meetingId=${meeting.meetingId} offset=$offsetMinutes"
                    }
                } else {
                    throw ex
                }
            }
        }
    }

    fun sendDueReminders() {
        val now = clock.instant()
        val stuckCutoff = now.minus(Duration.ofMinutes(stuckSendingThresholdMinutes))
        val reset = reminderRepository.resetStuckReminders(olderThan = stuckCutoff)
        if (reset > 0) log.warn { "Reset $reset stuck SENDING reminder(s) to PENDING" }

        val ready = reminderRepository.findDueBefore(before = now, limit = dispatchBatchSize)
        if (ready.isEmpty()) return

        ready.forEach { item ->
            processReminder(item = item, sentAt = now)
        }
    }

    private fun processReminder(item: ReadyReminder, sentAt: Instant) {
        val reminderId = item.reminder.id
        val claimToken = UUID.randomUUID().toString()

        if (!reminderRepository.claimReminder(reminderId = reminderId, claimToken = claimToken)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                item.attendingUserIds.forEach { userId ->
                    val commandBasicInfo =
                        CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                    val message =
                        buildReminderDm(
                            meetingTitle = item.meetingTitle,
                            offsetMinutes = item.reminder.offsetMinutes,
                            startAt = item.startAt,
                            commandBasicInfo = commandBasicInfo,
                        )
                    outboxRepository.save(
                        outboundMessagePort.toRow(message = message, basicInfo = commandBasicInfo),
                    )
                }
                if (!reminderRepository.markReminderSent(
                        reminderId = reminderId,
                        claimToken = claimToken,
                        sentAt = sentAt,
                    )
                ) {
                    // Recovery already flipped this row out of SENDING — roll back or we'd double-deliver it.
                    error("markReminderSent had no effect for reminder $reminderId — rolling back.")
                }
            }

        if (outcome.isFailure) {
            val ex = outcome.exceptionOrNull()!!
            log.error(ex) { "Meeting reminder dispatch failed: reminderId=$reminderId meetingId=${item.meetingId}" }
            if (!reminderRepository.markReminderFailed(
                    reminderId = reminderId,
                    claimToken = claimToken,
                    reason = ex.message ?: "unknown",
                )
            ) {
                log.warn { "markReminderFailed no-op for reminder $reminderId — recovery already reset or re-claimed." }
            }
        } else {
            log.info { "Meeting reminder enqueued: reminderId=$reminderId attendees=${item.attendingUserIds.size}" }
        }
    }
}

internal fun buildReminderDm(
    meetingTitle: String,
    offsetMinutes: Int,
    startAt: LocalDateTime,
    commandBasicInfo: CommandBasicInfo,
): OutboundMessage.ChannelMessage =
    OutboundMessage.ChannelMessage(
        target = ConversationTarget(id = commandBasicInfo.channel),
        content =
            MessageContent.Text(
                headline = "Meeting reminder — $meetingTitle",
                markdown =
                    "Your meeting *$meetingTitle* starts in $offsetMinutes minutes " +
                        "(at ${startAt.format(REMINDER_TIME_FORMAT)}).",
            ),
        detailType = CommandDetailType.MEETING_REMINDER,
    )
