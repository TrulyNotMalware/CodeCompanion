package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import dev.notypie.repository.meeting.schema.MeetingReminderSchema
import dev.notypie.repository.meeting.schema.toMeetingReminderDto
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime

open class MeetingReminderRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
    private val jpaMeetingReminderRepository: JpaMeetingReminderRepository,
) : MeetingReminderRepository {
    @Transactional(readOnly = true)
    override fun findActiveMeetingsInWindow(from: LocalDateTime, to: LocalDateTime): List<ReminderCandidateMeeting> =
        jpaMeetingRepository
            .findActiveByStartAtBetween(startAt = from, endAt = to)
            .map { meeting ->
                ReminderCandidateMeeting(
                    meetingId = meeting.id,
                    startAt = meeting.startAt,
                    attendingUserIds =
                        meeting.participants
                            .filter { it.isAttending }
                            .map { it.userId },
                )
            }

    // Moved, not kept: the (meeting_id, offset_minutes) unique key would leave the stale PENDING row as the only one.
    @Transactional
    override fun ensureReminder(
        meetingId: Long,
        offsetMinutes: Int,
        scheduledAt: Instant,
        startAt: LocalDateTime,
        now: Instant,
    ): Boolean {
        val existing =
            jpaMeetingReminderRepository.findByMeetingIdAndOffsetMinutes(
                meetingId = meetingId,
                offsetMinutes = offsetMinutes,
            )
        if (existing != null) {
            if (existing.status != MeetingReminderStatus.PENDING || existing.scheduledAt.isSameSecond(scheduledAt)) {
                return false
            }
            return jpaMeetingReminderRepository.realignPending(
                id = existing.id,
                observedAt = existing.scheduledAt,
                scheduledAt = scheduledAt,
                startAt = startAt,
                now = now,
            ) == 1
        }
        val reminder =
            MeetingReminderSchema(
                meeting = jpaMeetingRepository.getReferenceById(meetingId),
                offsetMinutes = offsetMinutes,
                scheduledAt = scheduledAt,
            )
        jpaMeetingReminderRepository.save(reminder)
        return true
    }

    @Transactional(readOnly = true)
    override fun reminderExists(meetingId: Long, offsetMinutes: Int): Boolean =
        jpaMeetingReminderRepository.findByMeetingIdAndOffsetMinutes(
            meetingId = meetingId,
            offsetMinutes = offsetMinutes,
        ) != null

    override fun claimReminder(reminderId: Long, claimToken: String, now: Instant): Boolean =
        jpaMeetingReminderRepository.claimReminder(id = reminderId, token = claimToken, now = now) == 1

    @Transactional
    override fun markReminderSent(reminderId: Long, claimToken: String, sentAt: Instant): Boolean =
        jpaMeetingReminderRepository.markSent(id = reminderId, token = claimToken, sentAt = sentAt) == 1

    @Transactional
    override fun markReminderFailed(
        reminderId: Long,
        claimToken: String,
        reason: String,
        now: Instant,
    ): Boolean =
        jpaMeetingReminderRepository.markFailed(id = reminderId, token = claimToken, reason = reason, now = now) == 1

    override fun resetStuckReminders(olderThan: Instant, now: Instant): Int =
        jpaMeetingReminderRepository.resetStuckSending(olderThan = olderThan, now = now)

    @Transactional
    override fun deleteByMeetingId(meetingId: Long): Int =
        jpaMeetingReminderRepository.deleteByMeetingId(meetingId = meetingId)

    @Transactional
    override fun discardReminder(reminderId: Long, scheduledAt: Instant): Boolean =
        jpaMeetingReminderRepository.discardPending(id = reminderId, observedAt = scheduledAt) == 1

    @Transactional(readOnly = true)
    override fun findDueBefore(before: Instant, limit: Int): List<ReadyReminder> =
        jpaMeetingReminderRepository
            .findPendingIdsBefore(before = before, pageable = PageRequest.of(0, limit))
            .takeIf { it.isNotEmpty() }
            ?.let { ids -> jpaMeetingReminderRepository.findWithMeetingAndParticipantsByIdIn(ids = ids) }
            .orEmpty()
            .map { schema ->
                ReadyReminder(
                    reminder = schema.toMeetingReminderDto(),
                    meetingId = schema.meeting.id,
                    meetingTitle = schema.meeting.name,
                    startAt = schema.meeting.startAt,
                    isCanceled = schema.meeting.isCanceled,
                    attendingUserIds =
                        schema.meeting.participants
                            .filter { it.isAttending }
                            .map { it.userId },
                )
            }
}
