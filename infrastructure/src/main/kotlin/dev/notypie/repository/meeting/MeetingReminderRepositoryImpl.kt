package dev.notypie.repository.meeting

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

    @Transactional
    override fun ensureReminder(meetingId: Long, offsetMinutes: Int, scheduledAt: Instant): Boolean {
        if (jpaMeetingReminderRepository.findByMeetingIdAndOffsetMinutes(
                meetingId = meetingId,
                offsetMinutes = offsetMinutes,
            ) != null
        ) {
            return false
        }
        val reminder =
            MeetingReminderSchema(
                // getReferenceById yields a lazy FK proxy — no need to load the full meeting just
                // to satisfy the meeting_id foreign key.
                meeting = jpaMeetingRepository.getReferenceById(meetingId),
                offsetMinutes = offsetMinutes,
                scheduledAt = scheduledAt,
            )
        jpaMeetingReminderRepository.save(reminder)
        return true
    }

    override fun reminderExists(meetingId: Long, offsetMinutes: Int): Boolean =
        jpaMeetingReminderRepository.findByMeetingIdAndOffsetMinutes(
            meetingId = meetingId,
            offsetMinutes = offsetMinutes,
        ) != null

    override fun claimReminder(reminderId: Long, claimToken: String): Boolean =
        jpaMeetingReminderRepository.claimReminder(id = reminderId, token = claimToken) == 1

    @Transactional
    override fun markReminderSent(reminderId: Long, claimToken: String, sentAt: Instant): Boolean =
        jpaMeetingReminderRepository.markSent(id = reminderId, token = claimToken, sentAt = sentAt) == 1

    @Transactional
    override fun markReminderFailed(reminderId: Long, claimToken: String, reason: String): Boolean =
        jpaMeetingReminderRepository.markFailed(id = reminderId, token = claimToken, reason = reason) == 1

    override fun resetStuckReminders(olderThan: Instant): Int =
        jpaMeetingReminderRepository.resetStuckSending(olderThan = olderThan)

    @Transactional
    override fun deleteByMeetingId(meetingId: Long): Int =
        jpaMeetingReminderRepository.deleteByMeetingId(meetingId = meetingId)

    override fun findDueBefore(before: Instant, limit: Int): List<ReadyReminder> =
        jpaMeetingReminderRepository
            .findPendingBefore(before = before, pageable = PageRequest.of(0, limit))
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
