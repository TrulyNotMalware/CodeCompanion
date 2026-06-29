package dev.notypie.application.service.meeting

import dev.notypie.application.common.runInTx
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.ReadyReminder
import dev.notypie.repository.meeting.ReminderCandidateMeeting
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class MeetingReminderSchedulingService(
    private val reminderRepository: MeetingReminderRepository,
    private val outboxRepository: MessageOutboxRepository,
    private val messageBuilder: MeetingReminderMessageBuilder,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
    @param:Value("\${meeting.reminder.offsets-minutes:15,5}")
    private val offsetsMinutes: List<Int> = listOf(15, 5),
    @param:Value("\${meeting.reminder.stuck-sending-threshold-minutes:5}")
    private val stuckSendingThresholdMinutes: Long = 5L,
    @param:Value("\${meeting.reminder.dispatch-batch-size:50}")
    private val dispatchBatchSize: Int = 50,
    @param:Value("\${meeting.reminder.materialize-lookback-minutes:2}")
    private val materializeLookbackMinutes: Long = 2L,
) {
    private val transactionTemplate: TransactionTemplate = TransactionTemplate(transactionManager)

    private val maxOffsetMinutes: Int = offsetsMinutes.maxOrNull() ?: 0

    /**
     * Phase A: For every non-canceled meeting whose `startAt` falls in the forward window
     * (now .. now + maxOffset, with a small lookback so a tick that just missed the boundary
     * still fires), ensure a `meeting_reminder` row exists per configured offset whose fire time
     * is not already long past. The unique constraint `(meeting_id, offset_minutes)` makes this
     * fully idempotent — a restart or duplicate tick simply finds the existing row.
     */
    fun materializeReminders() {
        if (maxOffsetMinutes <= 0) return
        val now = clock.instant()
        val zone = clock.zone
        // The forward window must extend to the *largest* offset so a 15-minute reminder is
        // materialized as soon as the meeting falls within 15 minutes of starting. The lookback
        // absorbs the case where a meeting's startAt slipped just behind a tick boundary.
        val windowFrom = LocalDateTime.ofInstant(now.minus(Duration.ofMinutes(materializeLookbackMinutes)), zone)
        val windowTo = LocalDateTime.ofInstant(now.plus(Duration.ofMinutes(maxOffsetMinutes.toLong())), zone)

        reminderRepository.findActiveMeetingsInWindow(from = windowFrom, to = windowTo).forEach { meeting ->
            materializeMeeting(meeting = meeting, now = now, zone = clock.zone)
        }
    }

    private fun materializeMeeting(meeting: ReminderCandidateMeeting, now: Instant, zone: java.time.ZoneId) {
        // No attending participants → no one to remind; skip without creating rows.
        if (meeting.attendingUserIds.isEmpty()) return
        val startInstant = meeting.startAt.atZone(zone).toInstant()

        offsetsMinutes.forEach { offsetMinutes ->
            val scheduledAt = startInstant.minus(Duration.ofMinutes(offsetMinutes.toLong()))
            // Skip an offset whose fire time is already long past — a meeting picked up late
            // should still get its nearer reminder but not a stale far one.
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
                // The unique constraint (meeting_id, offset_minutes) was the *expected* failure
                // mode (concurrent racing tick). We confirm the row actually exists before
                // swallowing — any other constraint violation must surface so it doesn't
                // masquerade as a benign race.
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
     * Phase B: Sends reminder DMs for reminders whose [ReadyReminder.reminder] fire time has
     * passed. Each reminder is claimed atomically (PENDING→SENDING) before sending so a
     * concurrent scheduler tick cannot double-send. Stuck SENDING rows from a prior crash are
     * reset to PENDING at the start of each tick.
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
     * Sends one reminder with two transaction boundaries so partial failures cannot leave the
     * system in inconsistent state:
     *
     *   1. **Claim** runs in its own transaction (the JPA repo's `@Modifying @Transactional`).
     *      A successful claim leaves the row in `SENDING` durably so the failure path below can
     *      transition it to `FAILED` even if step 2 rolls back.
     *
     *   2. **Send** wraps `(build → outbox.save for each attendee → markReminderSent)` in one
     *      [TransactionTemplate]. If `markReminderSent` returns false (recovery already moved the
     *      row out of `SENDING`) we mark the tx for rollback — that undoes the outbox saves so we
     *      don't deliver DMs the reminder state can't account for.
     *
     * If step 2 fails or rolls back, we then call `markReminderFailed` in a *new* transaction to
     * record the audit. Its CAS predicate (token + `status = 'SENDING'`) makes this safe: if
     * recovery already reset the row to PENDING, the call is a no-op and the next tick re-claims.
     */
    private fun processReminder(item: ReadyReminder, sentAt: Instant) {
        val reminderId = item.reminder.id
        // Per-claim token tied to this exact processReminder invocation. The CAS predicate on
        // every transition checks the token, so a stuck-row recovery + re-claim by another tick
        // cannot have its outcome silently overwritten by our markReminderFailed call.
        val claimToken = UUID.randomUUID().toString()

        if (!reminderRepository.claimReminder(reminderId = reminderId, claimToken = claimToken)) return

        val outcome: Result<Unit> =
            transactionTemplate.runInTx<Unit> {
                item.attendingUserIds.forEach { userId ->
                    val commandBasicInfo =
                        CommandBasicInfo.forOutbound(
                            publisherId = userId,
                            // Slack accepts a user_id as the DM channel target.
                            channel = userId,
                        )
                    val dmEvent =
                        messageBuilder.buildReminderDm(
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
                    // Crash-recovery sweep flipped this row out of SENDING (or another tick
                    // re-claimed it). Rolling back undoes the outbox saves above so we don't
                    // deliver DMs whose reminder row no longer represents our claim.
                    error(
                        "markReminderSent had no effect — reminder $reminderId is no longer " +
                            "SENDING under our claim token. Tx will be rolled back.",
                    )
                }
            }

        if (outcome.isFailure) {
            val ex = outcome.exceptionOrNull()!!
            log.error(ex) { "Meeting reminder dispatch failed: reminderId=$reminderId meetingId=${item.meetingId}" }
            // Fresh transaction. Token-keyed CAS guarantees we only mark FAILED for OUR claim;
            // if recovery + re-claim already happened, this is a no-op and we log it.
            if (!reminderRepository.markReminderFailed(
                    reminderId = reminderId,
                    claimToken = claimToken,
                    reason = ex.message ?: "unknown",
                )
            ) {
                log.warn {
                    "markReminderFailed had no effect — reminder $reminderId no longer holds our " +
                        "claim token. Recovery sweep already reset or another tick re-claimed."
                }
            }
        } else {
            log.info { "Meeting reminder enqueued: reminderId=$reminderId attendees=${item.attendingUserIds.size}" }
        }
    }
}
