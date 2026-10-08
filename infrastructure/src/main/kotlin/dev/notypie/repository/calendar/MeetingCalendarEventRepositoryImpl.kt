package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarSyncStatus
import dev.notypie.repository.calendar.schema.MeetingCalendarEvent
import dev.notypie.repository.calendar.schema.toMeetingCalendarEvent
import dev.notypie.repository.meeting.JpaMeetingRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

open class MeetingCalendarEventRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
    private val jpaMeetingCalendarEventRepository: JpaMeetingCalendarEventRepository,
    transactionManager: PlatformTransactionManager,
) : MeetingCalendarEventRepository {
    private val workerTransaction: TransactionTemplate = TransactionTemplate(transactionManager)

    @Transactional
    override fun enqueue(meetingId: Long, slackUserId: String, now: Instant) {
        jpaMeetingCalendarEventRepository.upsertDirty(
            meetingId = meetingId,
            slackUserId = slackUserId,
            now = now,
            createdAt = LocalDateTime.ofInstant(now, ZoneId.systemDefault()),
        )
    }

    @Transactional
    override fun touchMeeting(meetingId: Long, now: Instant): Int =
        jpaMeetingCalendarEventRepository.touchByMeetingId(meetingId = meetingId, now = now)

    @Transactional
    override fun touchMeetingByUid(meetingUid: UUID, now: Instant): Int =
        jpaMeetingCalendarEventRepository.touchByMeetingUid(meetingUid = meetingUid.toString(), now = now)

    @Transactional
    override fun touchOne(meetingId: Long, slackUserId: String, now: Instant): Boolean =
        jpaMeetingCalendarEventRepository.touchOne(meetingId = meetingId, slackUserId = slackUserId, now = now) == 1

    @Transactional
    override fun touchForUser(userId: String, meetingIds: Collection<Long>, now: Instant): Int =
        if (meetingIds.isEmpty()) {
            0
        } else {
            jpaMeetingCalendarEventRepository.touchBySlackUserId(
                slackUserId = userId,
                meetingIds = meetingIds,
                now = now,
            )
        }

    @Transactional(readOnly = true)
    override fun findDue(now: Instant, limit: Int): List<Long> =
        jpaMeetingCalendarEventRepository.findDueIds(now = now, pageable = PageRequest.of(0, limit))

    override fun claim(id: Long, token: String, now: Instant): MeetingCalendarEvent? {
        checkNoOuterTransaction(operation = "claim", subject = "rowId=$id")
        return workerTransaction.execute {
            if (jpaMeetingCalendarEventRepository.claim(id = id, token = token, now = now) == 1) {
                jpaMeetingCalendarEventRepository.findByIdOrNull(id = id)?.toMeetingCalendarEvent()
            } else {
                null
            }
        }
    }

    @Transactional(readOnly = true)
    override fun find(id: Long): MeetingCalendarEvent? =
        jpaMeetingCalendarEventRepository.findByIdOrNull(id = id)?.toMeetingCalendarEvent()

    override fun markSynced(
        id: Long,
        token: String,
        observedSeq: Long,
        googleEventId: String,
        now: Instant,
    ): Boolean {
        checkNoOuterTransaction(operation = "markSynced", subject = "rowId=$id")
        return workerTransaction.execute {
            jpaMeetingCalendarEventRepository.markSynced(
                id = id,
                token = token,
                observedSeq = observedSeq,
                googleEventId = googleEventId,
                now = now,
            )
        } == 1
    }

    override fun deleteSynced(id: Long, token: String, observedSeq: Long): Boolean {
        checkNoOuterTransaction(operation = "deleteSynced", subject = "rowId=$id")
        return workerTransaction.execute {
            jpaMeetingCalendarEventRepository.deleteSynced(id = id, token = token, observedSeq = observedSeq)
        } == 1
    }

    override fun releaseWithoutEvent(id: Long, token: String, now: Instant): Boolean {
        checkNoOuterTransaction(operation = "releaseWithoutEvent", subject = "rowId=$id")
        return workerTransaction.execute {
            jpaMeetingCalendarEventRepository.releaseWithoutEvent(id = id, token = token, now = now)
        } == 1
    }

    override fun retryLater(
        id: Long,
        token: String,
        observedSeq: Long,
        attempts: Int,
        nextAttemptAt: Instant,
        lastError: String,
        now: Instant,
    ): Boolean {
        checkNoOuterTransaction(operation = "retryLater", subject = "rowId=$id")
        return workerTransaction.execute {
            jpaMeetingCalendarEventRepository.retryLater(
                id = id,
                token = token,
                observedSeq = observedSeq,
                attempts = attempts,
                nextAttemptAt = nextAttemptAt,
                lastError = lastError,
                now = now,
            )
        } == 1
    }

    override fun markFailed(
        id: Long,
        token: String,
        observedSeq: Long,
        attempts: Int,
        lastError: String,
        now: Instant,
    ): CalendarSyncStatus? {
        checkNoOuterTransaction(operation = "markFailed", subject = "rowId=$id")
        return workerTransaction.execute {
            val changed =
                jpaMeetingCalendarEventRepository.markFailed(
                    id = id,
                    token = token,
                    observedSeq = observedSeq,
                    attempts = attempts,
                    lastError = lastError,
                    now = now,
                )
            if (changed == 1) jpaMeetingCalendarEventRepository.findByIdOrNull(id = id)?.status else null
        }
    }

    override fun resetStuck(olderThan: Instant, now: Instant): Int {
        checkNoOuterTransaction(operation = "resetStuck", subject = "olderThan=$olderThan")
        return workerTransaction.execute {
            jpaMeetingCalendarEventRepository.resetStuckSyncing(olderThan = olderThan, now = now)
        }
    }

    @Transactional
    override fun failPendingForUser(userId: String, lastError: String, now: Instant): Int =
        jpaMeetingCalendarEventRepository.failAllPendingForUser(slackUserId = userId, lastError = lastError, now = now)

    @Transactional(readOnly = true)
    override fun loadSyncView(meetingId: Long): CalendarMeetingView? =
        jpaMeetingRepository.findMeetingWithParticipants(meetingId = meetingId)?.let { meeting ->
            CalendarMeetingView(
                meetingId = meeting.id,
                meetingUid = meeting.meetingUid,
                title = meeting.name,
                reason = meeting.reason ?: "",
                startAt = meeting.startAt,
                endAt = meeting.endAt?.takeIf { it.isAfter(meeting.startAt) } ?: meeting.startAt.plusHours(1L),
                isCanceled = meeting.isCanceled,
                hostId = meeting.publisherId,
                attendingUserIds =
                    meeting.participants
                        .filter { it.isAttending }
                        .map { it.userId }
                        .toSet(),
            )
        }

    private fun checkNoOuterTransaction(operation: String, subject: String) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "MeetingCalendarEventRepository.$operation must run outside a transaction: each worker transition " +
                "commits on its own before or after the Google call, and under MariaDB snapshot isolation a write " +
                "after a read in one transaction fails with 1020 ($subject)"
        }
    }
}
