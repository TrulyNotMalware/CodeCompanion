package dev.notypie.application.service.calendar

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.CALENDAR_GOOGLE_PROPERTIES_PREFIX
import dev.notypie.application.service.standup.containFailure
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.common.escapeMarkup
import dev.notypie.impl.calendar.CalendarApiResult
import dev.notypie.impl.calendar.CalendarEventBody
import dev.notypie.impl.calendar.GoogleCalendarClient
import dev.notypie.repository.calendar.CalendarMeetingView
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.MeetingCalendarEventRepository
import dev.notypie.repository.calendar.schema.CalendarSyncStatus
import dev.notypie.repository.calendar.schema.MeetingCalendarEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

internal fun mirroredEventIdOf(meetingUid: UUID, slackUserId: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$meetingUid:$slackUserId".toByteArray(charset = Charsets.UTF_8))
        .toHexString()

class CalendarSyncService(
    private val queue: MeetingCalendarEventRepository,
    private val connections: GoogleCalendarConnectionRepository,
    private val tokenProvider: GoogleAccessTokenProvider,
    private val calendarClient: GoogleCalendarClient,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    appConfig: AppConfig,
) {
    companion object {
        const val REVOKED_ERROR: String = "google access revoked"
        const val NOT_CONNECTED_ERROR: String = "not connected"
        const val RATE_LIMITED_ERROR: String = "rate limited by Google"
        const val GRANT_REJECTED_MESSAGE: String =
            "Google no longer accepts CodeCompanion's access to your calendar (it was revoked or expired), so " +
                "meetings are no longer mirrored. Run `/meetup calendar connect` to reconnect."
        const val TOKEN_UNREADABLE_MESSAGE: String =
            "CodeCompanion can no longer use your saved Google Calendar connection, so meetings are no longer " +
                "mirrored. Run `/meetup calendar connect` to reconnect."
        private const val CLIENT_REJECTED_HINT =
            "check $CALENDAR_GOOGLE_PROPERTIES_PREFIX.client-id and client-secret"
        private const val API_DISABLED_HINT =
            "enable the Google Calendar API in the OAuth client's Google Cloud project"
        private const val MAX_BACKOFF_EXPONENT = 6
        private const val MAX_REASON_LENGTH = 200
        private val MAX_BACKOFF: Duration = Duration.ofMinutes(60L)
        private val MISCONFIGURED_RETRY_DELAY: Duration = Duration.ofMinutes(1L)
        private val RATE_LIMIT_MIN_DELAY: Duration = Duration.ofMinutes(2L)
    }

    private val batchSize: Int = appConfig.calendar.google.syncBatchSize
    private val maxAttempts: Int = appConfig.calendar.google.syncMaxAttempts
    private val stuckAfter: Duration = Duration.ofMinutes(appConfig.calendar.google.syncStuckMinutes)
    private val tickBudget: Duration = Duration.ofSeconds(appConfig.calendar.google.syncTickBudgetSeconds)
    private val noticeTemplate = TransactionTemplate(transactionManager)

    fun syncDue() {
        val tickStart = clock.instant()
        val reset = queue.resetStuck(olderThan = tickStart.minus(stuckAfter), now = tickStart)
        if (reset > 0) log.warn { "Reset $reset stuck SYNCING calendar row(s) to PENDING" }

        val due = queue.findDue(now = tickStart, limit = batchSize)
        val revokedUsers = mutableSetOf<String>()
        for ((index, id) in due.withIndex()) {
            if (Duration.between(tickStart, clock.instant()) >= tickBudget) {
                log.info { "Calendar sync tick budget spent: ${due.size - index} due row(s) left for the next tick" }
                return
            }
            if (syncContained(id = id, revokedUsers = revokedUsers) == TickControl.STOP) {
                log.info {
                    "Calendar sync tick ended early (rate limited, or Google rejected the deployment's setup): " +
                        "${due.size - index - 1} due row(s) left"
                }
                return
            }
        }
    }

    private fun syncContained(id: Long, revokedUsers: MutableSet<String>): TickControl {
        var claimed: Claim? = null
        var control = TickControl.CONTINUE
        containFailure(onFailure = { exception -> onRowFailure(id = id, claim = claimed, exception = exception) }) {
            val claim = claim(id = id, revokedUsers = revokedUsers) ?: return@containFailure
            claimed = claim
            control = sync(claim = claim, revokedUsers = revokedUsers)
        }
        return control
    }

    private fun onRowFailure(id: Long, claim: Claim?, exception: Exception) {
        if (claim == null) {
            log.error(exception) { "Calendar sync row failed: rowId=$id" }
            return
        }
        log.error(exception) {
            "Calendar sync row failed: rowId=$id meetingId=${claim.row.meetingId} userId=${claim.row.slackUserId}"
        }
        containFailure(
            onFailure = { backoffFailure ->
                log.error(backoffFailure) { "Calendar sync could not schedule a retry of a failed row: rowId=$id" }
            },
        ) {
            backoff(
                claim = claim,
                reason = exception.message ?: exception.javaClass.simpleName,
                title = null,
            )
        }
    }

    private fun claim(id: Long, revokedUsers: Set<String>): Claim? {
        if (revokedUsers.isNotEmpty()) {
            val peeked = queue.find(id = id)
            if (peeked != null && peeked.slackUserId in revokedUsers) return null
        }
        val token = UUID.randomUUID().toString()
        val row = queue.claim(id = id, token = token, now = clock.instant()) ?: return null
        return Claim(row = row, token = token)
    }

    private fun sync(claim: Claim, revokedUsers: MutableSet<String>): TickControl {
        val row = claim.row
        val view = queue.loadSyncView(meetingId = row.meetingId)
        val desired = view?.takeIf { it.wantsEventFor(userId = row.slackUserId) }
        val eventId = row.googleEventId
        val plan =
            when {
                desired != null ->
                    Plan.Write(
                        view = desired,
                        eventId = eventId,
                        mirroredId = mirroredEventIdOf(meetingUid = desired.meetingUid, slackUserId = row.slackUserId),
                    )
                eventId != null -> Plan.Remove(eventId = eventId)
                view != null ->
                    Plan.Remove(
                        eventId = mirroredEventIdOf(meetingUid = view.meetingUid, slackUserId = row.slackUserId),
                    )
                else -> {
                    settleWithoutEvent(claim = claim)
                    return TickControl.CONTINUE
                }
            }
        val title = view?.title
        var result =
            when (val access = accessTokenFor(claim = claim, title = title, revokedUsers = revokedUsers)) {
                is Access.Token -> mirror(accessToken = access.accessToken, plan = plan)
                is Access.Denied -> return access.control
            }
        if (result == CalendarApiResult.Unauthorized) {
            tokenProvider.evict(userId = row.slackUserId)
            result =
                when (val access = accessTokenFor(claim = claim, title = title, revokedUsers = revokedUsers)) {
                    is Access.Token -> mirror(accessToken = access.accessToken, plan = plan)
                    is Access.Denied -> return access.control
                }
        }
        return settle(claim = claim, plan = plan, result = result, title = title)
    }

    private fun CalendarMeetingView.wantsEventFor(userId: String): Boolean =
        !isCanceled && (userId == hostId || userId in attendingUserIds)

    private fun accessTokenFor(claim: Claim, title: String?, revokedUsers: MutableSet<String>): Access =
        when (val access = tokenProvider.accessToken(userId = claim.row.slackUserId)) {
            is AccessTokenResult.Granted -> Access.Token(accessToken = access.accessToken)

            AccessTokenResult.NotConnected -> {
                val settled =
                    failClaim(
                        claim = claim,
                        attempts = claim.row.attempts,
                        lastError = NOT_CONNECTED_ERROR,
                        now = clock.instant(),
                    )
                log.info {
                    "Calendar sync row of a user without an active Google connection settled as " +
                        "${settled ?: "claim lost"}: rowId=${claim.row.id} userId=${claim.row.slackUserId}"
                }
                Access.Denied(control = TickControl.CONTINUE)
            }

            is AccessTokenResult.Revoked -> {
                try {
                    revokeUser(userId = claim.row.slackUserId, revoked = access)
                    failClaim(
                        claim = claim,
                        attempts = claim.row.attempts,
                        lastError = REVOKED_ERROR,
                        now = clock.instant(),
                    )
                } finally {
                    revokedUsers += claim.row.slackUserId
                }
                Access.Denied(control = TickControl.CONTINUE)
            }

            is AccessTokenResult.Misconfigured -> {
                pauseTick(claim = claim, message = access.message, hint = CLIENT_REJECTED_HINT)
                Access.Denied(control = TickControl.STOP)
            }

            is AccessTokenResult.Unavailable -> {
                backoff(claim = claim, reason = access.message, title = title)
                Access.Denied(control = TickControl.CONTINUE)
            }
        }

    private fun mirror(accessToken: String, plan: Plan): CalendarApiResult =
        when (plan) {
            is Plan.Remove -> calendarClient.delete(accessToken = accessToken, eventId = plan.eventId)

            is Plan.Write -> {
                val body = eventBody(view = plan.view)
                val existing = plan.eventId
                if (existing == null) {
                    insertOrPatch(accessToken = accessToken, eventId = plan.mirroredId, body = body)
                } else {
                    val patched = calendarClient.patch(accessToken = accessToken, eventId = existing, event = body)
                    if (patched == CalendarApiResult.Gone) {
                        insertOrPatch(accessToken = accessToken, eventId = plan.mirroredId, body = body)
                    } else {
                        patched
                    }
                }
            }
        }

    private fun insertOrPatch(accessToken: String, eventId: String, body: CalendarEventBody): CalendarApiResult {
        val inserted = calendarClient.insert(accessToken = accessToken, eventId = eventId, event = body)
        if (inserted != CalendarApiResult.AlreadyExists) return inserted
        return calendarClient.patch(accessToken = accessToken, eventId = eventId, event = body)
    }

    private fun settle(
        claim: Claim,
        plan: Plan,
        result: CalendarApiResult,
        title: String?,
    ): TickControl {
        when (result) {
            is CalendarApiResult.Ok ->
                when (plan) {
                    is Plan.Write -> recordEvent(claim = claim, eventId = result.eventId)
                    is Plan.Remove -> settleWithoutEvent(claim = claim)
                }

            CalendarApiResult.Gone ->
                when (plan) {
                    is Plan.Write ->
                        backoff(
                            claim = claim,
                            reason = "Google answered 404 to an insert",
                            title = title,
                        )

                    is Plan.Remove -> settleWithoutEvent(claim = claim)
                }

            CalendarApiResult.AlreadyExists ->
                backoff(
                    claim = claim,
                    reason = "Google answered 409 Conflict",
                    title = title,
                )

            CalendarApiResult.Unauthorized ->
                backoff(
                    claim = claim,
                    reason = "Google rejected a fresh access token",
                    title = title,
                )

            is CalendarApiResult.RateLimited -> {
                val delay = maxOf(result.retryAfter ?: Duration.ZERO, RATE_LIMIT_MIN_DELAY)
                val requeued = requeueWithoutAttempt(claim = claim, delay = delay, lastError = RATE_LIMITED_ERROR)
                log.warn {
                    "Calendar sync rate limited by Google, tick stopped: rowId=${claim.row.id} " +
                        "userId=${claim.row.slackUserId} retryIn=$delay requeued=$requeued"
                }
                return TickControl.STOP
            }

            is CalendarApiResult.Misconfigured -> {
                pauseTick(claim = claim, message = result.message, hint = API_DISABLED_HINT)
                return TickControl.STOP
            }

            is CalendarApiResult.Failed ->
                backoff(
                    claim = claim,
                    reason = result.message,
                    title = title,
                )
        }
        return TickControl.CONTINUE
    }

    private fun recordEvent(claim: Claim, eventId: String) {
        val recorded =
            queue.markSynced(
                id = claim.row.id,
                token = claim.token,
                observedSeq = claim.row.changeSeq,
                googleEventId = eventId,
                now = clock.instant(),
            )
        if (recorded) return
        log.warn {
            "Calendar sync lost its claim after writing event $eventId: rowId=${claim.row.id} " +
                "meetingId=${claim.row.meetingId} userId=${claim.row.slackUserId}; the event id is derived from the " +
                "meeting, so the next pass of a live row re-inserts the same id, gets 409 and patches it"
        }
    }

    private fun settleWithoutEvent(claim: Claim) {
        if (queue.deleteSynced(id = claim.row.id, token = claim.token, observedSeq = claim.row.changeSeq)) return
        if (!queue.releaseWithoutEvent(id = claim.row.id, token = claim.token, now = clock.instant())) {
            log.warn { "Calendar sync lost its claim before settling: rowId=${claim.row.id}" }
        }
    }

    private fun backoff(claim: Claim, reason: String, title: String?) {
        val attempts = claim.row.attempts + 1
        val now = clock.instant()
        if (attempts >= maxAttempts) {
            giveUp(claim = claim, attempts = attempts, reason = reason, title = title, now = now)
            return
        }
        val delay = backoffDelay(attempts = attempts)
        val scheduled =
            queue.retryLater(
                id = claim.row.id,
                token = claim.token,
                observedSeq = claim.row.changeSeq,
                attempts = attempts,
                nextAttemptAt = now.plus(delay),
                lastError = reason,
                now = now,
            )
        if (scheduled) {
            log.warn {
                "Calendar sync failed, retrying in $delay: rowId=${claim.row.id} meetingId=${claim.row.meetingId} " +
                    "userId=${claim.row.slackUserId} attempts=$attempts reason=$reason"
            }
        } else {
            log.warn { "Calendar sync lost its claim before scheduling a retry: rowId=${claim.row.id}" }
        }
    }

    private fun backoffDelay(attempts: Int): Duration =
        minOf(MAX_BACKOFF, Duration.ofMinutes(1L shl minOf(attempts - 1, MAX_BACKOFF_EXPONENT)))

    private fun giveUp(
        claim: Claim,
        attempts: Int,
        reason: String,
        title: String?,
        now: Instant,
    ) {
        when (val settled = failClaim(claim = claim, attempts = attempts, lastError = reason, now = now)) {
            CalendarSyncStatus.FAILED -> {
                noticeTemplate.executeWithoutResult {
                    outboundStager.stageCalendarDirectMessage(
                        userId = claim.row.slackUserId,
                        text = syncFailureMessage(title = title, reason = reason),
                        appId = "",
                        publisher = eventPublisher,
                    )
                }
                log.warn {
                    "Calendar sync gave up: rowId=${claim.row.id} meetingId=${claim.row.meetingId} " +
                        "userId=${claim.row.slackUserId} attempts=$attempts reason=$reason"
                }
            }

            CalendarSyncStatus.PENDING, CalendarSyncStatus.SYNCING, CalendarSyncStatus.SYNCED ->
                log.info {
                    "Calendar sync row changed during its last attempt and was requeued instead of failed: " +
                        "rowId=${claim.row.id} status=$settled"
                }

            null -> log.warn { "Calendar sync lost its claim before giving up: rowId=${claim.row.id}" }
        }
    }

    private fun failClaim(
        claim: Claim,
        attempts: Int,
        lastError: String,
        now: Instant,
    ): CalendarSyncStatus? =
        queue.markFailed(
            id = claim.row.id,
            token = claim.token,
            observedSeq = claim.row.changeSeq,
            attempts = attempts,
            lastError = lastError,
            now = now,
        )

    private fun revokeUser(userId: String, revoked: AccessTokenResult.Revoked) {
        val cause = revoked.cause
        noticeTemplate.executeWithoutResult {
            val now = clock.instant()
            val matched =
                connections.markRevoked(
                    userId = userId,
                    observedEncryptedRefreshToken = revoked.observedEncryptedRefreshToken,
                    now = now,
                    reason = cause.lastError,
                )
            if (!matched) {
                log.info {
                    "Google Calendar grant unusable (${cause.lastError}), but the stored connection changed " +
                        "meanwhile (reconnected, disconnected or already revoked), so nothing else is failed: " +
                        "userId=$userId"
                }
                return@executeWithoutResult
            }
            val failed = queue.failPendingForUser(userId = userId, lastError = REVOKED_ERROR, now = now)
            outboundStager.stageCalendarDirectMessage(
                userId = userId,
                text = reconnectMessage(cause = cause),
                appId = "",
                publisher = eventPublisher,
            )
            log.warn {
                "Google Calendar connection revoked (${cause.lastError}), $failed calendar sync row(s) failed: " +
                    "userId=$userId"
            }
        }
    }

    private fun reconnectMessage(cause: RevocationCause): String =
        when (cause) {
            RevocationCause.GRANT_REJECTED -> GRANT_REJECTED_MESSAGE
            RevocationCause.TOKEN_UNREADABLE -> TOKEN_UNREADABLE_MESSAGE
        }

    private fun pauseTick(claim: Claim, message: String, hint: String) {
        val requeued = requeueWithoutAttempt(claim = claim, delay = MISCONFIGURED_RETRY_DELAY, lastError = message)
        log.error {
            "Calendar sync tick stopped: $message; $hint. rowId=${claim.row.id} userId=${claim.row.slackUserId} " +
                "requeued=$requeued"
        }
    }

    private fun requeueWithoutAttempt(claim: Claim, delay: Duration, lastError: String): Boolean {
        val now = clock.instant()
        return queue.retryLater(
            id = claim.row.id,
            token = claim.token,
            observedSeq = claim.row.changeSeq,
            attempts = claim.row.attempts,
            nextAttemptAt = now.plus(delay),
            lastError = lastError,
            now = now,
        )
    }

    private fun syncFailureMessage(title: String?, reason: String): String {
        val meeting = title?.let { "*${it.escapeMarkup()}*" } ?: "a meeting"
        return "CodeCompanion couldn't sync $meeting to your Google Calendar " +
            "(${reason.take(MAX_REASON_LENGTH).escapeMarkup()}). It will not retry; reconnect with " +
            "`/meetup calendar connect` if this keeps happening."
    }

    private fun eventBody(view: CalendarMeetingView): CalendarEventBody {
        val managedLine = "Managed by CodeCompanion (meeting ${view.meetingUid})"
        return CalendarEventBody(
            summary = view.title,
            description = if (view.reason.isBlank()) managedLine else "${view.reason}\n\n$managedLine",
            start = view.startAt,
            end = view.endAt,
            timeZone = clock.zone,
            meetingUid = view.meetingUid,
        )
    }

    private data class Claim(
        val row: MeetingCalendarEvent,
        val token: String,
    )

    private sealed interface Plan {
        data class Write(
            val view: CalendarMeetingView,
            val eventId: String?,
            val mirroredId: String,
        ) : Plan

        data class Remove(
            val eventId: String,
        ) : Plan
    }

    private sealed interface Access {
        data class Token(
            val accessToken: String,
        ) : Access {
            override fun toString(): String = "Token(accessToken=****)"
        }

        data class Denied(
            val control: TickControl,
        ) : Access
    }

    private enum class TickControl {
        CONTINUE,
        STOP,
    }
}
