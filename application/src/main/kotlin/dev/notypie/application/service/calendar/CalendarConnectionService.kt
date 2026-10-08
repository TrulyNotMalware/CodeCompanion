package dev.notypie.application.service.calendar

import dev.notypie.application.configurations.AppConfig
import dev.notypie.domain.command.entity.event.CalendarConnectionAction
import dev.notypie.domain.command.entity.event.CalendarConnectionPayload
import dev.notypie.domain.command.entity.event.CalendarConnectionRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.common.escapeMarkup
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.GoogleOAuthException
import dev.notypie.impl.calendar.GoogleTokenGrant
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.GoogleOAuthStateRepository
import dev.notypie.repository.calendar.MeetingCalendarEventRepository
import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.repository.meeting.MeetingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64

private val log = KotlinLogging.logger {}

class CalendarConnectionService(
    private val connectionRepository: GoogleCalendarConnectionRepository,
    private val stateRepository: GoogleOAuthStateRepository,
    private val queue: MeetingCalendarEventRepository,
    private val meetingRepository: MeetingRepository,
    private val oauthClient: GoogleOAuthClient,
    private val tokenCipher: TokenCipher,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
    appConfig: AppConfig,
) : CalendarConnectionCallback {
    companion object {
        private const val STATE_BYTES = 32
        const val CONNECT_USAGE: String = "Run `/calendar connect` to link your Google Calendar."
        const val DISCONNECT_REASON: String = "disconnect"
        const val RECONNECT_REASON: String = "replaced by a new connection"
        const val DISCONNECTED_ERROR: String = "disconnected"
        const val BACKFILL_HORIZON_YEARS: Long = 2L
        const val BACKFILL_LOOKBACK_DAYS: Long = 1L
        private const val DEFAULT_MEETING_HOURS = 1L
    }

    private val stateTtl: Duration = Duration.ofMinutes(appConfig.calendar.google.stateTtlMinutes)
    private val writeTemplate = TransactionTemplate(transactionManager)
    private val random = SecureRandom()
    private val stateEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    @EventListener
    fun handle(event: CalendarConnectionRequestEvent) {
        val payload = event.payload
        writeTemplate.executeWithoutResult {
            when (payload.action) {
                CalendarConnectionAction.CONNECT -> connect(payload = payload)
                CalendarConnectionAction.DISCONNECT -> disconnect(payload = payload)
                CalendarConnectionAction.STATUS -> status(payload = payload)
            }
        }
    }

    override fun completeConnection(code: String?, state: String?, error: String?): CalendarConnectionOutcome {
        if (state.isNullOrBlank()) return CalendarConnectionOutcome.INVALID_STATE
        val userId =
            writeTemplate.execute {
                val now = clock.instant()
                stateRepository.consume(state = state, now = now)?.also { stateRepository.deleteExpired(before = now) }
            } ?: return CalendarConnectionOutcome.INVALID_STATE
        if (!error.isNullOrBlank()) {
            log.info { "Google Calendar consent denied: userId=$userId" }
            return CalendarConnectionOutcome.DENIED
        }
        if (code.isNullOrBlank()) return CalendarConnectionOutcome.INVALID_STATE

        val grant =
            try {
                oauthClient.exchangeCode(code = code)
            } catch (exception: GoogleOAuthException) {
                log.warn(exception) { "Google Calendar code exchange failed: userId=$userId" }
                return CalendarConnectionOutcome.EXCHANGE_FAILED
            }
        if (!grant.grants(scope = GoogleOAuthClient.CALENDAR_EVENTS_SCOPE)) {
            log.info { "Google Calendar scope not granted: userId=$userId scopes=${grant.scopes}" }
            val active = writeTemplate.execute { connectionRepository.find(userId = userId) }?.isActive ?: false
            if (!active) oauthClient.revoke(token = grant.refreshToken)
            return CalendarConnectionOutcome.SCOPE_DENIED
        }
        val encrypted = tokenCipher.encrypt(plaintext = grant.refreshToken)
        try {
            storeConnection(userId = userId, grant = grant, encrypted = encrypted)
        } catch (conflict: DataAccessException) {
            if (!conflict.isStoreConflict()) throw conflict
            log.info(conflict) { "Google Calendar connection store conflicted, retrying once: userId=$userId" }
            try {
                storeConnection(userId = userId, grant = grant, encrypted = encrypted)
            } catch (retryConflict: DataAccessException) {
                if (!retryConflict.isStoreConflict()) throw retryConflict
                log.error(retryConflict) {
                    "Google Calendar connection could not be stored after one retry; the consent state is spent, so " +
                        "the user has to connect again: userId=$userId"
                }
                return CalendarConnectionOutcome.STORE_FAILED
            }
        }
        log.info { "Google Calendar connected: userId=$userId" }
        return CalendarConnectionOutcome.CONNECTED
    }

    private fun DataAccessException.isStoreConflict(): Boolean =
        this is DataIntegrityViolationException || this is ConcurrencyFailureException

    private fun storeConnection(userId: String, grant: GoogleTokenGrant, encrypted: String) {
        writeTemplate.executeWithoutResult {
            val now = clock.instant()
            val saved =
                connectionRepository.saveActive(
                    userId = userId,
                    googleSubject = grant.subject,
                    googleEmail = grant.email,
                    encryptedRefreshToken = encrypted,
                    now = now,
                )
            saved.replaced
                ?.takeIf { replaced -> replaced.isDifferentAccountFrom(subject = grant.subject, email = grant.email) }
                ?.let { replaced ->
                    requestRevocation(
                        userId = userId,
                        googleSubject = replaced.googleSubject,
                        encryptedRefreshToken = replaced.encryptedRefreshToken,
                        reason = RECONNECT_REASON,
                    )
                }
            queueUpcomingMeetings(userId = userId, now = now)
            outboundStager.stageCalendarDirectMessage(
                userId = userId,
                text = "Google Calendar connected${saved.connection.accountSuffix()}.",
                appId = "",
                publisher = eventPublisher,
            )
        }
    }

    private fun queueUpcomingMeetings(userId: String, now: Instant) {
        val localNow = LocalDateTime.now(clock)
        val inRange =
            meetingRepository.getMeetingsByUserIdInRange(
                userId = userId,
                startAt = localNow.minusDays(BACKFILL_LOOKBACK_DAYS),
                endAt = localNow.plusYears(BACKFILL_HORIZON_YEARS),
            )
        val hosted = inRange.filter { meeting -> meeting.isHostedLiveMeetingOf(userId = userId, localNow = localNow) }
        hosted.forEach { meeting -> queue.enqueue(meetingId = meeting.meetingId, slackUserId = userId, now = now) }
        val revived =
            queue.touchForUser(userId = userId, meetingIds = inRange.map { meeting -> meeting.meetingId }, now = now)
        log.info {
            "Google Calendar connection queued ${hosted.size} hosted meeting(s) and revived $revived existing " +
                "row(s): userId=$userId"
        }
    }

    private fun MeetingDto.isHostedLiveMeetingOf(userId: String, localNow: LocalDateTime): Boolean {
        val effectiveEnd = endAt?.takeIf { it.isAfter(startAt) } ?: startAt.plusHours(DEFAULT_MEETING_HOURS)
        return creator == userId && !isCanceled && effectiveEnd.isAfter(localNow)
    }

    private fun connect(payload: CalendarConnectionPayload) {
        val basicInfo = payload.responseBasicInfo
        val state = newState()
        stateRepository.issue(state = state, userId = payload.userId, expiresAt = clock.instant().plus(stateTtl))
        val link = "<${oauthClient.authorizationUrl(state = state).escapeMarkup()}|Connect Google Calendar>"
        outboundStager.stageCalendarDirectMessage(
            userId = payload.userId,
            text =
                "$link\nThis link expires in ${stateTtl.toMinutes()} minutes and can be used once. " +
                    "Google will ask you to allow CodeCompanion to manage events on your calendar.",
            appId = basicInfo.appId,
            publisher = eventPublisher,
        )
        val existing = connectionRepository.find(userId = payload.userId)
        val prefix =
            if (existing != null && existing.isActive) {
                "Google Calendar is already connected${existing.accountSuffix()}; connecting again replaces it. "
            } else {
                ""
            }
        outboundStager.stageCalendarEphemeral(
            text = "${prefix}I sent you a direct message with a link to connect your Google Calendar.",
            basicInfo = basicInfo,
            publisher = eventPublisher,
        )
    }

    private fun disconnect(payload: CalendarConnectionPayload) {
        val basicInfo = payload.responseBasicInfo
        val connection = connectionRepository.find(userId = payload.userId)
        if (connection == null) {
            outboundStager.stageCalendarEphemeral(
                text = "Google Calendar is not connected. $CONNECT_USAGE",
                basicInfo = basicInfo,
                publisher = eventPublisher,
            )
            return
        }
        connectionRepository.delete(userId = payload.userId)
        stateRepository.deleteForUser(userId = payload.userId)
        queue.failPendingForUser(userId = payload.userId, lastError = DISCONNECTED_ERROR, now = clock.instant())
        requestRevocation(
            userId = payload.userId,
            googleSubject = connection.googleSubject,
            encryptedRefreshToken = connection.encryptedRefreshToken,
            reason = DISCONNECT_REASON,
        )
        outboundStager.stageCalendarEphemeral(
            text = "Google Calendar disconnected. CodeCompanion's access at Google is being revoked.",
            basicInfo = basicInfo,
            publisher = eventPublisher,
        )
    }

    private fun status(payload: CalendarConnectionPayload) {
        val connection = connectionRepository.find(userId = payload.userId)
        val text =
            when {
                connection == null -> "Google Calendar is not connected. $CONNECT_USAGE"
                connection.isActive ->
                    "Google Calendar is connected${connection.accountSuffix()} since " +
                        "${slackDate(instant = connection.connectedAt)}."

                else -> {
                    val revokedOn =
                        connection.revokedAt
                            ?.let { instant ->
                                " on ${slackDate(instant = instant)}"
                            }.orEmpty()
                    "Google Calendar access was revoked$revokedOn. $CONNECT_USAGE"
                }
            }
        outboundStager.stageCalendarEphemeral(
            text = text,
            basicInfo = payload.responseBasicInfo,
            publisher = eventPublisher,
        )
    }

    private fun requestRevocation(
        userId: String,
        googleSubject: String?,
        encryptedRefreshToken: String,
        reason: String,
    ) {
        applicationEventPublisher.publishEvent(
            GoogleTokenRevocationRequested(
                userId = userId,
                googleSubject = googleSubject,
                encryptedRefreshToken = encryptedRefreshToken,
                reason = reason,
            ),
        )
    }

    private fun newState(): String = stateEncoder.encodeToString(ByteArray(STATE_BYTES).also { random.nextBytes(it) })

    private fun CalendarConnection.accountSuffix(): String = googleEmail?.let { " as ${it.escapeMarkup()}" }.orEmpty()

    private fun CalendarConnection.isDifferentAccountFrom(subject: String?, email: String?): Boolean =
        when {
            googleSubject != null && subject != null -> googleSubject != subject
            googleEmail != null && email != null -> !googleEmail.equals(other = email, ignoreCase = true)
            else -> false
        }

    private fun slackDate(instant: Instant): String = "<!date^${instant.epochSecond}^{date_short} {time}|$instant>"
}
