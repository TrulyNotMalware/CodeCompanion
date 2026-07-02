package dev.notypie.application.service.meeting

import dev.notypie.application.common.runInTx
import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.event.SendSlackMessageEvent
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.ReadyReminder
import dev.notypie.repository.meeting.ReminderCandidateMeeting
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
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
    private val stager: OutboundMessageStager,
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

    /**
     * Phase A: ensures a `meeting_reminder` row exists per configured offset for every non-canceled
     * meeting starting within the forward window. The unique constraint `(meeting_id, offset_minutes)`
     * makes this idempotent across restarts and duplicate ticks.
     */
    fun materializeReminders() {
        if (maxOffsetMinutes <= 0) return
        val now = clock.instant()
        val zone = clock.zone
        // Window reaches the largest offset (so a 15-min reminder is created once the meeting is
        // within 15 min of starting); the lookback absorbs a startAt that slipped behind a tick.
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
            // Skip an offset whose fire time is already long past — a late-picked-up meeting still
            // gets its nearer reminder but not a stale far one.
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
                // The unique constraint is the expected race outcome. Confirm the row exists before
                // swallowing — any other violation must surface rather than masquerade as a race.
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

    /**
     * Phase B: sends DMs for reminders whose fire time has passed. Stuck SENDING rows from a prior
     * crash are reset to PENDING first; each reminder is then claimed atomically before sending.
     */
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

    /**
     * Sends one reminder across three transaction boundaries so a partial failure can't leave
     * inconsistent state: (1) claim commits PENDING→SENDING in its own tx; (2) build + outbox saves
     * + markReminderSent run in one tx that rolls back if markReminderSent is a no-op (recovery
     * raced us); (3) on failure, markReminderFailed records the audit in a fresh tx. The per-claim
     * token gates every CAS so only our own claim's outcome can be acknowledged.
     */
    private fun processReminder(item: ReadyReminder, sentAt: Instant) {
        val reminderId = item.reminder.id
        val claimToken = UUID.randomUUID().toString()

        if (!reminderRepository.claimReminder(reminderId = reminderId, claimToken = claimToken)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                item.attendingUserIds.forEach { userId ->
                    val commandBasicInfo =
                        CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)
                    val dmEvent =
                        buildReminderDm(
                            stager = stager,
                            meetingTitle = item.meetingTitle,
                            offsetMinutes = item.reminder.offsetMinutes,
                            startAt = item.startAt,
                            commandBasicInfo = commandBasicInfo,
                        )
                    outboxRepository.save(dmEvent.toOutboxMessage())
                }
                if (!reminderRepository.markReminderSent(
                        reminderId = reminderId,
                        claimToken = claimToken,
                        sentAt = sentAt,
                    )
                ) {
                    // Recovery flipped this row out of SENDING; roll back so we don't deliver DMs
                    // the reminder row no longer accounts for.
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

/**
 * Builds the pre-meeting reminder DM. A scheduler tick has no `trigger_id`, so this is a plain
 * `chat.postMessage` (the recipient's user_id rides as [CommandBasicInfo.channel]).
 */
internal fun buildReminderDm(
    stager: OutboundMessageStager,
    meetingTitle: String,
    offsetMinutes: Int,
    startAt: LocalDateTime,
    commandBasicInfo: CommandBasicInfo,
): SendSlackMessageEvent =
    stager.stage(
        message =
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
            ),
        basicInfo = commandBasicInfo,
    ) as SendSlackMessageEvent
