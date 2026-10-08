package dev.notypie.application.service.calendar

import ch.qos.logback.classic.spi.ILoggingEvent
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.MutableClock
import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.application.service.meeting.captureErrorLogs
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.CalendarApiResult
import dev.notypie.impl.calendar.GoogleCalendarClient
import dev.notypie.impl.calendar.createCalendarEventBody
import dev.notypie.repository.calendar.CalendarMeetingView
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.MeetingCalendarEventRepository
import dev.notypie.repository.calendar.schema.CalendarSyncStatus
import dev.notypie.repository.calendar.schema.MeetingCalendarEvent
import dev.notypie.schema.createCalendarMeetingView
import dev.notypie.schema.createMeetingCalendarEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class CalendarSyncServiceTest :
    BehaviorSpec({
        val start = Instant.parse("2026-10-08T01:00:00Z")
        val zone = ZoneId.of("Asia/Seoul")
        val meetingUid = UUID.fromString("7d2c3f9e-4b1a-4c55-9a8e-0f6b2d1c3e4a")
        val host = "U_HOST"
        val guest = "U_GUEST"
        val meetingStart = LocalDateTime.of(2031, 1, 6, 15, 0)
        val managedLine = "Managed by CodeCompanion (meeting $meetingUid)"
        val mirroredId = "5a93d5c4725fb62f61c010c223433adb2bc4827760944208f1d62debba03f933"
        val guestMirroredId = "feaf4b3f6294f0c8d7a99365e3c5d7cd71ef1a7c5c92653bfed2188ccf4fbf70"
        val storedCiphertext = "v1.stored-refresh-token"
        val expectedBody =
            createCalendarEventBody(
                summary = "Sprint review",
                description = "Demo the release\n\n$managedLine",
                start = meetingStart,
                end = meetingStart.plusHours(1L),
                timeZone = zone,
                meetingUid = meetingUid,
            )

        fun view(
            meetingId: Long = 7L,
            title: String = "Sprint review",
            reason: String = "Demo the release",
            endAt: LocalDateTime = meetingStart.plusHours(1L),
            isCanceled: Boolean = false,
            attending: Set<String> = setOf(guest),
        ): CalendarMeetingView =
            createCalendarMeetingView(
                meetingId = meetingId,
                meetingUid = meetingUid,
                title = title,
                reason = reason,
                startAt = meetingStart,
                endAt = endAt,
                isCanceled = isCanceled,
                hostId = host,
                attendingUserIds = attending,
            )

        fun row(
            id: Long = 1L,
            meetingId: Long = 7L,
            user: String = host,
            eventId: String? = null,
            attempts: Int = 0,
        ): MeetingCalendarEvent =
            createMeetingCalendarEvent(
                id = id,
                meetingId = meetingId,
                slackUserId = user,
                googleEventId = eventId,
                status = CalendarSyncStatus.SYNCING,
                changeSeq = 3L,
                attempts = attempts,
            )

        fun googleConfig(maxAttempts: Int = 8): AppConfig =
            AppConfig(
                calendar =
                    AppConfig.Calendar(
                        google = AppConfig.Calendar.Google(enabled = true, syncMaxAttempts = maxAttempts),
                    ),
            )

        class Harness(
            val clock: Clock = Clock.fixed(start, zone),
            appConfig: AppConfig = googleConfig(),
            val transactionManager: PlatformTransactionManager = createStubTransactionManager(),
        ) {
            val queue = mockk<MeetingCalendarEventRepository>()
            val connections = mockk<GoogleCalendarConnectionRepository>()
            val tokenProvider = mockk<GoogleAccessTokenProvider>()
            val calendarClient = mockk<GoogleCalendarClient>()
            val staged = mutableListOf<OutboundMessage>()
            val stagedInfos = mutableListOf<CommandBasicInfo>()
            val claimTokens = mutableListOf<String>()
            val stagedInsideTransaction = mutableListOf<Boolean>()
            val failedInsideTransaction = mutableListOf<Boolean>()
            val revokedInsideTransaction = mutableListOf<Boolean>()
            val failedPendingInsideTransaction = mutableListOf<Boolean>()
            val transactionsSeen = mutableListOf<Any?>()
            val stager =
                mockk<OutboundMessageStager> {
                    every { stage(message = capture(staged), basicInfo = capture(stagedInfos)) } answers {
                        stagedInsideTransaction += TransactionSynchronizationManager.isActualTransactionActive()
                        transactionsSeen += currentTransaction()
                        mockk(relaxed = true)
                    }
                }
            val service =
                CalendarSyncService(
                    queue = queue,
                    connections = connections,
                    tokenProvider = tokenProvider,
                    calendarClient = calendarClient,
                    outboundStager = stager,
                    eventPublisher = mockk<EventPublisher>(relaxed = true),
                    transactionManager = transactionManager,
                    clock = clock,
                    appConfig = appConfig,
                )

            init {
                every { queue.resetStuck(olderThan = any(), now = any()) } returns 0
                every {
                    queue.markSynced(id = any(), token = any(), observedSeq = any(), googleEventId = any(), now = any())
                } returns true
                every { queue.deleteSynced(id = any(), token = any(), observedSeq = any()) } returns true
                every { queue.releaseWithoutEvent(id = any(), token = any(), now = any()) } returns true
                every {
                    queue.retryLater(
                        id = any(),
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        nextAttemptAt = any(),
                        lastError = any(),
                        now = any(),
                    )
                } returns true
                every {
                    queue.markFailed(
                        id = any(),
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        lastError = any(),
                        now = any(),
                    )
                } answers {
                    failedInsideTransaction += TransactionSynchronizationManager.isActualTransactionActive()
                    CalendarSyncStatus.FAILED
                }
                every { queue.failPendingForUser(userId = any(), lastError = any(), now = any()) } answers {
                    failedPendingInsideTransaction += TransactionSynchronizationManager.isActualTransactionActive()
                    transactionsSeen += currentTransaction()
                    1
                }
                every {
                    connections.markRevoked(
                        userId = any(),
                        observedEncryptedRefreshToken = any(),
                        now = any(),
                        reason = any(),
                    )
                } answers {
                    revokedInsideTransaction += TransactionSynchronizationManager.isActualTransactionActive()
                    transactionsSeen += currentTransaction()
                    true
                }
                every { tokenProvider.accessToken(userId = any()) } returns
                    AccessTokenResult.Granted(accessToken = "ya29.token")
                every { tokenProvider.evict(userId = any()) } just Runs
            }

            fun due(vararg rows: Pair<MeetingCalendarEvent, CalendarMeetingView?>) {
                every { queue.findDue(now = any(), limit = any()) } returns rows.map { (dueRow, _) -> dueRow.id }
                rows.forEach { (dueRow, dueView) ->
                    every { queue.claim(id = dueRow.id, token = capture(claimTokens), now = any()) } returns dueRow
                    every { queue.find(id = dueRow.id) } returns dueRow
                    every { queue.loadSyncView(meetingId = dueRow.meetingId) } returns dueView
                }
            }

            // The JDBC connection bound to the running transaction: one instance per transaction.
            fun currentTransaction(): Any? =
                (transactionManager as? DataSourceTransactionManager)?.dataSource?.let { dataSource ->
                    TransactionSynchronizationManager.getResource(dataSource)
                }

            fun sync(): List<ILoggingEvent> =
                captureErrorLogs(loggerName = CalendarSyncService::class.java.name) { service.syncDue() }

            fun token(): String = claimTokens.single()

            fun directMessages(): List<Pair<String, String>> =
                staged.zip(stagedInfos).map { (message, info) ->
                    val channelMessage = message as OutboundMessage.ChannelMessage
                    info.channel to (channelMessage.content as MessageContent.Text).markdown
                }

            fun verifyNoRetry() {
                verify(exactly = 0) {
                    queue.retryLater(
                        id = any(),
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        nextAttemptAt = any(),
                        lastError = any(),
                        now = any(),
                    )
                }
            }

            fun verifyNoFailure() {
                verify(exactly = 0) {
                    queue.markFailed(
                        id = any(),
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        lastError = any(),
                        now = any(),
                    )
                }
            }

            fun verifyNoRetryOrFailure() {
                verifyNoRetry()
                verifyNoFailure()
            }

            fun verifyNoGoogleCall() {
                verify(exactly = 0) { calendarClient.insert(accessToken = any(), eventId = any(), event = any()) }
                verify(exactly = 0) { calendarClient.patch(accessToken = any(), eventId = any(), event = any()) }
                verify(exactly = 0) { calendarClient.delete(accessToken = any(), eventId = any()) }
            }
        }

        given("the host's row of a live meeting with no event yet") {
            val h = Harness()
            h.due(row() to view())
            every {
                h.calendarClient.insert(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
            } returns CalendarApiResult.Ok(eventId = mirroredId)

            val errors = h.sync()

            then("stuck rows are swept first, one batch of due rows is read, and the claim's snapshot is used") {
                verifyOrder {
                    h.queue.resetStuck(olderThan = start.minus(Duration.ofMinutes(10L)), now = start)
                    h.queue.findDue(now = start, limit = 20)
                    h.queue.claim(id = 1L, token = h.token(), now = start)
                    h.queue.loadSyncView(meetingId = 7L)
                }
                verify(exactly = 0) { h.queue.find(id = any()) }
            }

            then("the event is inserted under the host's id for the meeting with the title, reason and clock zone") {
                verify(exactly = 1) {
                    h.calendarClient.insert(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
                }
            }

            then("the event id is recorded against the change_seq read at the claim") {
                verify(exactly = 1) {
                    h.queue.markSynced(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        googleEventId = mirroredId,
                        now = start,
                    )
                }
                h.verifyNoRetryOrFailure()
                errors.shouldBeEmpty()
            }
        }

        given("a meeting with no reason that lasts ninety minutes") {
            val h = Harness()
            h.due(row() to view(reason = "", endAt = meetingStart.plusMinutes(90L)))
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                CalendarApiResult.Ok(eventId = mirroredId)

            h.sync()

            then("the description is only the managed line and the event ends at the view's end") {
                verify(exactly = 1) {
                    h.calendarClient.insert(
                        accessToken = "ya29.token",
                        eventId = mirroredId,
                        event =
                            createCalendarEventBody(
                                summary = "Sprint review",
                                description = managedLine,
                                start = meetingStart,
                                end = meetingStart.plusMinutes(90L),
                                timeZone = zone,
                                meetingUid = meetingUid,
                            ),
                    )
                }
            }
        }

        given("the host's row whose event a reset or crashed pass already inserted") {
            val h = Harness()
            h.due(row() to view())
            every {
                h.calendarClient.insert(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
            } returns CalendarApiResult.AlreadyExists
            every {
                h.calendarClient.patch(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
            } returns CalendarApiResult.Ok(eventId = mirroredId)

            val errors = h.sync()

            then("the 409 turns into a patch of that id, which is recorded, instead of a second event") {
                verifyOrder {
                    h.calendarClient.insert(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
                    h.calendarClient.patch(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
                    h.queue.markSynced(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        googleEventId = mirroredId,
                        now = start,
                    )
                }
                h.verifyNoRetryOrFailure()
                errors.shouldBeEmpty()
            }
        }

        given("an attending guest whose event already exists") {
            `when`("Google patches it") {
                val h = Harness()
                h.due(row(user = guest, eventId = "evt-1") to view())
                every {
                    h.calendarClient.patch(
                        accessToken = "ya29.token",
                        eventId = "evt-1",
                        event = expectedBody,
                    )
                } returns
                    CalendarApiResult.Ok(eventId = "evt-1")

                val errors = h.sync()

                then("the same event id is recorded and nothing is inserted") {
                    verify(exactly = 1) {
                        h.queue.markSynced(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            googleEventId = "evt-1",
                            now = start,
                        )
                    }
                    verify(exactly = 0) { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) }
                    errors.shouldBeEmpty()
                }
            }

            `when`("the user deleted it at Google, so the patch is Gone") {
                val h = Harness()
                h.due(row(user = guest, eventId = "evt-1") to view())
                every { h.calendarClient.patch(accessToken = any(), eventId = "evt-1", event = any()) } returns
                    CalendarApiResult.Gone
                every {
                    h.calendarClient.insert(accessToken = "ya29.token", eventId = guestMirroredId, event = expectedBody)
                } returns CalendarApiResult.Ok(eventId = guestMirroredId)

                h.sync()

                then(
                    "the event is inserted again under the guest's own id for the meeting, which replaces the old one",
                ) {
                    verifyOrder {
                        h.calendarClient.patch(accessToken = "ya29.token", eventId = "evt-1", event = expectedBody)
                        h.calendarClient.insert(
                            accessToken = "ya29.token",
                            eventId = guestMirroredId,
                            event = expectedBody,
                        )
                        h.queue.markSynced(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            googleEventId = guestMirroredId,
                            now = start,
                        )
                    }
                }
            }
        }

        given("an attending guest whose event is gone and cannot be recreated") {
            val h = Harness()
            h.due(row(user = guest, eventId = "evt-1") to view())
            every { h.calendarClient.patch(accessToken = any(), eventId = "evt-1", event = any()) } returns
                CalendarApiResult.Gone
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                CalendarApiResult.Gone

            h.sync()

            then("the row backs off instead of being recorded or dropped") {
                verify(exactly = 1) {
                    h.queue.retryLater(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 1,
                        nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                        lastError = "Google answered 404 to an insert",
                        now = start,
                    )
                }
                verify(exactly = 0) {
                    h.queue.markSynced(
                        id = any(),
                        token = any(),
                        observedSeq = any(),
                        googleEventId = any(),
                        now = any(),
                    )
                }
                verify(exactly = 0) { h.queue.deleteSynced(id = any(), token = any(), observedSeq = any()) }
            }
        }

        given("a patched event whose row the stuck sweep reset meanwhile") {
            val h = Harness()
            h.due(row(user = guest, eventId = "evt-1") to view())
            every { h.calendarClient.patch(accessToken = any(), eventId = "evt-1", event = any()) } returns
                CalendarApiResult.Ok(eventId = "evt-1")
            every {
                h.queue.markSynced(id = 1L, token = any(), observedSeq = 3L, googleEventId = "evt-1", now = start)
            } returns false

            val errors = h.sync()

            then("nothing is orphaned, so no ERROR is logged: the row still names that event") {
                errors.shouldBeEmpty()
                h.verifyNoRetryOrFailure()
            }
        }

        given("a row whose event must no longer exist") {
            listOf(
                "the meeting was canceled" to view(isCanceled = true),
                "the guest declined" to view(attending = emptySet()),
                "the meeting is gone" to null,
            ).forEach { (label, dueView) ->
                `when`("$label and Google deletes the event") {
                    val h = Harness()
                    h.due(row(user = guest, eventId = "evt-1") to dueView)
                    every { h.calendarClient.delete(accessToken = "ya29.token", eventId = "evt-1") } returns
                        CalendarApiResult.Ok(eventId = "evt-1")

                    val errors = h.sync()

                    then("the row is deleted while its claim and change_seq still match") {
                        verify(exactly = 1) { h.queue.deleteSynced(id = 1L, token = h.token(), observedSeq = 3L) }
                        verify(exactly = 0) { h.queue.releaseWithoutEvent(id = any(), token = any(), now = any()) }
                        errors.shouldBeEmpty()
                    }
                }
            }

            `when`("the event is already gone at Google") {
                val h = Harness()
                h.due(row(user = guest, eventId = "evt-1") to view(isCanceled = true))
                every { h.calendarClient.delete(accessToken = any(), eventId = "evt-1") } returns CalendarApiResult.Gone

                h.sync()

                then("that counts as deleted") {
                    verify(exactly = 1) { h.queue.deleteSynced(id = 1L, token = h.token(), observedSeq = 3L) }
                    h.verifyNoRetryOrFailure()
                }
            }

            `when`("a hook bumped change_seq while the delete was in flight") {
                val h = Harness()
                h.due(row(user = guest, eventId = "evt-1") to view(isCanceled = true))
                every { h.calendarClient.delete(accessToken = any(), eventId = "evt-1") } returns
                    CalendarApiResult.Ok(eventId = "evt-1")
                every { h.queue.deleteSynced(id = 1L, token = any(), observedSeq = 3L) } returns false

                h.sync()

                then("the row is released without its event id so the next pass decides again") {
                    verify(exactly = 1) { h.queue.releaseWithoutEvent(id = 1L, token = h.token(), now = start) }
                }
            }

            `when`("the stuck sweep took the row before either settlement") {
                val h = Harness()
                h.due(row(user = guest, eventId = "evt-1") to view(isCanceled = true))
                every { h.calendarClient.delete(accessToken = any(), eventId = "evt-1") } returns
                    CalendarApiResult.Ok(eventId = "evt-1")
                every { h.queue.deleteSynced(id = 1L, token = any(), observedSeq = 3L) } returns false
                every { h.queue.releaseWithoutEvent(id = 1L, token = any(), now = start) } returns false

                val errors = h.sync()

                then("both misses are only logged: nothing else is written and no ERROR is raised") {
                    verify(exactly = 1) { h.queue.deleteSynced(id = 1L, token = h.token(), observedSeq = 3L) }
                    verify(exactly = 1) { h.queue.releaseWithoutEvent(id = 1L, token = h.token(), now = start) }
                    h.verifyNoRetryOrFailure()
                    errors.shouldBeEmpty()
                }
            }
        }

        given("a row with no stored event id that no longer needs one") {
            `when`("the meeting still exists") {
                val h = Harness()
                h.due(row(user = guest) to view(attending = emptySet()))
                every { h.calendarClient.delete(accessToken = "ya29.token", eventId = guestMirroredId) } returns
                    CalendarApiResult.Gone

                val errors = h.sync()

                then(
                    "the event an unrecorded insert may have created is deleted under the user's own id, and the " +
                        "row is settled",
                ) {
                    verifyOrder {
                        h.calendarClient.delete(accessToken = "ya29.token", eventId = guestMirroredId)
                        h.queue.deleteSynced(id = 1L, token = h.token(), observedSeq = 3L)
                    }
                    verify(exactly = 0) { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) }
                    h.verifyNoRetryOrFailure()
                    errors.shouldBeEmpty()
                }
            }

            `when`("the meeting row is gone") {
                val h = Harness()
                h.due(row(user = guest) to null)

                val errors = h.sync()

                then("the row is settled without asking for a token or calling Google: there is no uid to derive") {
                    verify(exactly = 1) { h.queue.deleteSynced(id = 1L, token = h.token(), observedSeq = 3L) }
                    verify(exactly = 0) { h.tokenProvider.accessToken(userId = any()) }
                    h.verifyNoGoogleCall()
                    errors.shouldBeEmpty()
                }
            }
        }

        given("an insert whose answer was lost, after which the meeting is canceled") {
            val h = Harness()
            h.due(row() to view())
            every {
                h.calendarClient.insert(
                    accessToken = "ya29.token",
                    eventId = mirroredId,
                    event = expectedBody,
                )
            } returns
                CalendarApiResult.Failed(statusCode = null, message = "request failed: HttpTimeoutException")
            val firstErrors = h.sync()
            h.due(row() to view(isCanceled = true))
            every { h.calendarClient.delete(accessToken = "ya29.token", eventId = mirroredId) } returns
                CalendarApiResult.Ok(eventId = mirroredId)

            val secondErrors = h.sync()

            then("the first pass backs off without an event id") {
                verify(exactly = 1) {
                    h.queue.retryLater(
                        id = 1L,
                        token = h.claimTokens.first(),
                        observedSeq = 3L,
                        attempts = 1,
                        nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                        lastError = "request failed: HttpTimeoutException",
                        now = start,
                    )
                }
            }

            then("the second pass deletes the event Google may have created under the derived id and settles") {
                verifyOrder {
                    h.calendarClient.insert(accessToken = "ya29.token", eventId = mirroredId, event = expectedBody)
                    h.calendarClient.delete(accessToken = "ya29.token", eventId = mirroredId)
                    h.queue.deleteSynced(id = 1L, token = h.claimTokens.last(), observedSeq = 3L)
                }
                verify(exactly = 1) { h.calendarClient.delete(accessToken = any(), eventId = any()) }
                h.verifyNoFailure()
                firstErrors.shouldBeEmpty()
                secondErrors.shouldBeEmpty()
            }
        }

        given("an inserted event whose row the stuck sweep reset meanwhile") {
            val h = Harness()
            h.due(row() to view())
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                CalendarApiResult.Ok(eventId = mirroredId)
            every {
                h.queue.markSynced(id = 1L, token = any(), observedSeq = 3L, googleEventId = mirroredId, now = start)
            } returns false

            val errors = h.sync()

            then(
                "no ERROR is logged, since the next pass re-inserts the same id and patches it, and nothing is written",
            ) {
                errors.shouldBeEmpty()
                h.verifyNoRetryOrFailure()
                verify(exactly = 0) { h.queue.deleteSynced(id = any(), token = any(), observedSeq = any()) }
                verify(exactly = 0) { h.queue.releaseWithoutEvent(id = any(), token = any(), now = any()) }
            }
        }

        given("a user who is no longer connected") {
            val h = Harness()
            h.due(row() to view())
            every { h.tokenProvider.accessToken(userId = host) } returns AccessTokenResult.NotConnected

            val errors = h.sync()

            then("only this row is failed with the seq it was claimed at; no other row is touched, no DM is sent") {
                verify(exactly = 1) {
                    h.queue.markFailed(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 0,
                        lastError = CalendarSyncService.NOT_CONNECTED_ERROR,
                        now = start,
                    )
                }
                verify(exactly = 0) { h.queue.failPendingForUser(userId = any(), lastError = any(), now = any()) }
                CalendarSyncService.NOT_CONNECTED_ERROR shouldBe "not connected"
                h.verifyNoGoogleCall()
                h.verifyNoRetry()
                h.staged.shouldBeEmpty()
                errors.shouldBeEmpty()
            }
        }

        given("a user who is no longer connected, on a row a hook touched during the pass") {
            val h = Harness()
            h.due(row() to view())
            every { h.tokenProvider.accessToken(userId = host) } returns AccessTokenResult.NotConnected
            every {
                h.queue.markFailed(
                    id = 1L,
                    token = any(),
                    observedSeq = 3L,
                    attempts = any(),
                    lastError = any(),
                    now = any(),
                )
            } returns CalendarSyncStatus.PENDING

            val errors = h.sync()

            then("markFailed requeues the row instead of failing it, and nothing else is written or sent") {
                verify(exactly = 1) {
                    h.queue.markFailed(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 0,
                        lastError = CalendarSyncService.NOT_CONNECTED_ERROR,
                        now = start,
                    )
                }
                h.verifyNoRetry()
                h.verifyNoGoogleCall()
                h.staged.shouldBeEmpty()
                errors.shouldBeEmpty()
            }
        }

        given("a user whose grant Google revoked, with two rows due, and another user's row after them") {
            val h = Harness(transactionManager = createH2TransactionManager())
            h.due(row(id = 1L) to view(), row(id = 2L) to view(), row(id = 3L, user = guest) to view())
            every { h.tokenProvider.accessToken(userId = host) } returns
                AccessTokenResult.Revoked(
                    observedEncryptedRefreshToken = storedCiphertext,
                    cause = RevocationCause.GRANT_REJECTED,
                )
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                CalendarApiResult.Ok(eventId = "evt-guest")

            val errors = h.sync()

            then(
                "the connection is revoked against the observed token and the user's PENDING rows are failed first, " +
                    "then the claimed row is failed",
            ) {
                verifyOrder {
                    h.connections.markRevoked(
                        userId = host,
                        observedEncryptedRefreshToken = storedCiphertext,
                        now = start,
                        reason = "refresh rejected: invalid_grant",
                    )
                    h.queue.failPendingForUser(
                        userId = host,
                        lastError = CalendarSyncService.REVOKED_ERROR,
                        now = start,
                    )
                    h.queue.markFailed(
                        id = 1L,
                        token = any(),
                        observedSeq = 3L,
                        attempts = 0,
                        lastError = CalendarSyncService.REVOKED_ERROR,
                        now = start,
                    )
                }
                verify(exactly = 1) {
                    h.queue.markFailed(
                        id = any(),
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        lastError = any(),
                        now = any(),
                    )
                }
                verify(exactly = 1) {
                    h.connections.markRevoked(
                        userId = any(),
                        observedEncryptedRefreshToken = any(),
                        now = any(),
                        reason = any(),
                    )
                }
                verify(exactly = 1) { h.queue.failPendingForUser(userId = any(), lastError = any(), now = any()) }
                errors.shouldBeEmpty()
            }

            then("the user is told once, in words that cover an expired grant too, how to reconnect") {
                h.directMessages() shouldBe listOf(host to CalendarSyncService.GRANT_REJECTED_MESSAGE)
                CalendarSyncService.GRANT_REJECTED_MESSAGE shouldBe
                    "Google no longer accepts CodeCompanion's access to your calendar (it was revoked or expired), " +
                    "so meetings are no longer mirrored. Run `/meetup calendar connect` to reconnect."
            }

            then(
                "markFailed runs outside any transaction, and the revoke, the PENDING failures and the DM share one",
            ) {
                h.failedInsideTransaction shouldBe listOf(false)
                h.revokedInsideTransaction shouldBe listOf(true)
                h.failedPendingInsideTransaction shouldBe listOf(true)
                h.stagedInsideTransaction shouldBe listOf(true)
                h.transactionsSeen.size shouldBe 3
                h.transactionsSeen.forEach { transaction ->
                    transaction.shouldNotBeNull()
                    transaction shouldBeSameInstanceAs h.transactionsSeen.first()
                }
            }

            then("the user's later row is not claimed, while the other user's row still syncs") {
                verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                verify(exactly = 1) { h.queue.claim(id = 3L, token = any(), now = any()) }
                verify(exactly = 1) {
                    h.queue.markSynced(
                        id = 3L,
                        token = any(),
                        observedSeq = 3L,
                        googleEventId = "evt-guest",
                        now = start,
                    )
                }
                verify(exactly = 1) { h.tokenProvider.accessToken(userId = host) }
                h.verifyNoRetry()
            }
        }

        given("a revoked grant whose connection a reconnect or disconnect changed before it was recorded") {
            val h = Harness()
            h.due(row(id = 1L) to view(), row(id = 2L) to view())
            every { h.tokenProvider.accessToken(userId = host) } returns
                AccessTokenResult.Revoked(
                    observedEncryptedRefreshToken = storedCiphertext,
                    cause = RevocationCause.GRANT_REJECTED,
                )
            every {
                h.connections.markRevoked(
                    userId = host,
                    observedEncryptedRefreshToken = storedCiphertext,
                    now = start,
                    reason = any(),
                )
            } returns false

            val errors = h.sync()

            then("only the claimed row is failed: no other row is failed and no reconnect DM is sent") {
                verify(exactly = 1) {
                    h.queue.markFailed(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 0,
                        lastError = CalendarSyncService.REVOKED_ERROR,
                        now = start,
                    )
                }
                verify(exactly = 0) { h.queue.failPendingForUser(userId = any(), lastError = any(), now = any()) }
                h.staged.shouldBeEmpty()
                errors.shouldBeEmpty()
            }

            then("the user's next row still waits for the next tick") {
                verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
            }
        }

        given("a user whose stored refresh token can no longer be decrypted") {
            val h = Harness()
            h.due(row() to view())
            every { h.tokenProvider.accessToken(userId = host) } returns
                AccessTokenResult.Revoked(
                    observedEncryptedRefreshToken = storedCiphertext,
                    cause = RevocationCause.TOKEN_UNREADABLE,
                )

            val errors = h.sync()

            then("the connection is revoked with that cause and the DM does not blame Google") {
                verify(exactly = 1) {
                    h.connections.markRevoked(
                        userId = host,
                        observedEncryptedRefreshToken = storedCiphertext,
                        now = start,
                        reason = "stored token unreadable",
                    )
                }
                h.directMessages() shouldBe listOf(host to CalendarSyncService.TOKEN_UNREADABLE_MESSAGE)
                CalendarSyncService.TOKEN_UNREADABLE_MESSAGE shouldBe
                    "CodeCompanion can no longer use your saved Google Calendar connection, so meetings are no " +
                    "longer mirrored. Run `/meetup calendar connect` to reconnect."
                errors.shouldBeEmpty()
            }
        }

        given("a revoked grant whose revocation transaction fails") {
            `when`("the retry can still be scheduled") {
                val h = Harness()
                h.due(row(id = 1L) to view(), row(id = 2L) to view(), row(id = 3L, user = guest) to view())
                every { h.tokenProvider.accessToken(userId = host) } returns
                    AccessTokenResult.Revoked(
                        observedEncryptedRefreshToken = storedCiphertext,
                        cause = RevocationCause.GRANT_REJECTED,
                    )
                every {
                    h.connections.markRevoked(
                        userId = host,
                        observedEncryptedRefreshToken = any(),
                        now = any(),
                        reason = any(),
                    )
                } throws IllegalStateException("db down")
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Ok(eventId = "evt-guest")

                val errors = h.sync()

                then("the claimed row is never failed ahead of a revocation that did not commit") {
                    h.verifyNoFailure()
                    verify(exactly = 0) { h.queue.failPendingForUser(userId = any(), lastError = any(), now = any()) }
                    h.staged.shouldBeEmpty()
                }

                then("the failure is one ERROR and the row backs off, so the next pass tries the revocation again") {
                    errors.size shouldBe 1
                    errors.single().formattedMessage shouldContain "rowId=1 meetingId=7 userId=U_HOST"
                    verify(exactly = 1) {
                        h.queue.retryLater(
                            id = 1L,
                            token = any(),
                            observedSeq = 3L,
                            attempts = 1,
                            nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                            lastError = "db down",
                            now = start,
                        )
                    }
                }

                then("the user is still skipped for the rest of the tick, and another user's row still syncs") {
                    verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                    verify(exactly = 1) {
                        h.queue.markSynced(
                            id = 3L,
                            token = any(),
                            observedSeq = 3L,
                            googleEventId = "evt-guest",
                            now = start,
                        )
                    }
                }
            }

            `when`("the database stays down for the retry as well") {
                val h = Harness()
                h.due(row(id = 1L) to view(), row(id = 2L) to view())
                every { h.tokenProvider.accessToken(userId = host) } returns
                    AccessTokenResult.Revoked(
                        observedEncryptedRefreshToken = storedCiphertext,
                        cause = RevocationCause.GRANT_REJECTED,
                    )
                every {
                    h.connections.markRevoked(
                        userId = host,
                        observedEncryptedRefreshToken = any(),
                        now = any(),
                        reason = any(),
                    )
                } throws IllegalStateException("db down")
                every {
                    h.queue.retryLater(
                        id = 1L,
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        nextAttemptAt = any(),
                        lastError = any(),
                        now = any(),
                    )
                } throws IllegalStateException("db down")

                val errors = h.sync()

                then("the claimed row is left SYNCING for the stuck sweep and the user is still skipped") {
                    h.verifyNoFailure()
                    errors.size shouldBe 2
                    verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                }
            }
        }

        given("a token refreshed after a 401 that is no longer usable") {
            `when`("the second token request finds the grant revoked") {
                val h = Harness()
                h.due(row(id = 1L) to view(), row(id = 2L) to view())
                every { h.tokenProvider.accessToken(userId = host) } returns
                    AccessTokenResult.Granted(accessToken = "ya29.stale") andThen
                    AccessTokenResult.Revoked(
                        observedEncryptedRefreshToken = storedCiphertext,
                        cause = RevocationCause.GRANT_REJECTED,
                    )
                every { h.calendarClient.insert(accessToken = "ya29.stale", eventId = any(), event = any()) } returns
                    CalendarApiResult.Unauthorized

                val errors = h.sync()

                then("the revocation runs, then the claimed row is failed, and the user's next row is skipped") {
                    verifyOrder {
                        h.calendarClient.insert(accessToken = "ya29.stale", eventId = mirroredId, event = expectedBody)
                        h.tokenProvider.evict(userId = host)
                        h.connections.markRevoked(
                            userId = host,
                            observedEncryptedRefreshToken = storedCiphertext,
                            now = start,
                            reason = "refresh rejected: invalid_grant",
                        )
                        h.queue.markFailed(
                            id = 1L,
                            token = any(),
                            observedSeq = 3L,
                            attempts = 0,
                            lastError = CalendarSyncService.REVOKED_ERROR,
                            now = start,
                        )
                    }
                    verify(exactly = 1) { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) }
                    verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                    h.directMessages() shouldBe listOf(host to CalendarSyncService.GRANT_REJECTED_MESSAGE)
                    h.verifyNoRetry()
                    errors.shouldBeEmpty()
                }
            }

            `when`("the second token request finds the OAuth client rejected") {
                val h = Harness()
                h.due(row(id = 1L, attempts = 4) to view(), row(id = 2L, user = guest) to view())
                every { h.tokenProvider.accessToken(userId = host) } returns
                    AccessTokenResult.Granted(accessToken = "ya29.stale") andThen
                    AccessTokenResult.Misconfigured(message = "Google rejected the OAuth client: unauthorized_client")
                every { h.calendarClient.insert(accessToken = "ya29.stale", eventId = any(), event = any()) } returns
                    CalendarApiResult.Unauthorized

                val errors = h.sync()

                then("the row is requeued with its attempt count unchanged and the tick ends with one ERROR") {
                    verify(exactly = 1) {
                        h.queue.retryLater(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            attempts = 4,
                            nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                            lastError = "Google rejected the OAuth client: unauthorized_client",
                            now = start,
                        )
                    }
                    verify(exactly = 1) { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) }
                    verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                    h.verifyNoFailure()
                    h.staged.shouldBeEmpty()
                    errors.size shouldBe 1
                    errors.single().formattedMessage shouldContain "unauthorized_client"
                }
            }
        }

        given("the Calendar API is disabled for the OAuth client's project while two rows are due") {
            val h = Harness()
            h.due(row(id = 1L, attempts = 2) to view(), row(id = 2L, user = guest) to view())
            val disabled =
                "Google Calendar API is not enabled for the OAuth client's project (accessNotConfigured): " +
                    "Google Calendar API has not been used in project 123456 before or it is disabled."
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                CalendarApiResult.Misconfigured(message = disabled)

            val errors = h.sync()

            then("the row is requeued in one minute with its attempt count unchanged; nothing is failed or sent") {
                verify(exactly = 1) {
                    h.queue.retryLater(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 2,
                        nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                        lastError = disabled,
                        now = start,
                    )
                }
                h.verifyNoFailure()
                h.staged.shouldBeEmpty()
            }

            then("one ERROR names the reason and what to enable, and the tick ends before the next row is claimed") {
                errors.size shouldBe 1
                errors.single().formattedMessage shouldContain "accessNotConfigured"
                errors.single().formattedMessage shouldContain "enable the Google Calendar API"
                verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
            }
        }

        given("Google rejects the OAuth client while two rows are due") {
            val h = Harness()
            h.due(row(id = 1L, attempts = 2) to view(), row(id = 2L, user = guest) to view())
            every { h.tokenProvider.accessToken(userId = host) } returns
                AccessTokenResult.Misconfigured(message = "Google rejected the OAuth client: invalid_client")

            val errors = h.sync()

            then("the row is requeued in one minute with its attempt count unchanged and nothing is failed") {
                verify(exactly = 1) {
                    h.queue.retryLater(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 2,
                        nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                        lastError = "Google rejected the OAuth client: invalid_client",
                        now = start,
                    )
                }
                h.verifyNoFailure()
                verify(exactly = 0) {
                    h.connections.markRevoked(
                        userId = any(),
                        observedEncryptedRefreshToken = any(),
                        now = any(),
                        reason = any(),
                    )
                }
                h.verifyNoGoogleCall()
                h.staged.shouldBeEmpty()
            }

            then(
                "one ERROR names the error code and the client keys, and the tick ends before the next row is claimed",
            ) {
                errors.size shouldBe 1
                errors.single().formattedMessage shouldContain "invalid_client"
                errors.single().formattedMessage shouldContain "slack.app.calendar.google.client-id and client-secret"
                verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
            }
        }

        given("an access token Google no longer accepts") {
            `when`("the refreshed token works") {
                val h = Harness()
                h.due(row() to view())
                every { h.tokenProvider.accessToken(userId = host) } returns
                    AccessTokenResult.Granted(accessToken = "ya29.stale") andThen
                    AccessTokenResult.Granted(accessToken = "ya29.fresh")
                every {
                    h.calendarClient.insert(
                        accessToken = "ya29.stale",
                        eventId = mirroredId,
                        event = any(),
                    )
                } returns
                    CalendarApiResult.Unauthorized
                every {
                    h.calendarClient.insert(
                        accessToken = "ya29.fresh",
                        eventId = mirroredId,
                        event = any(),
                    )
                } returns
                    CalendarApiResult.Ok(eventId = "evt-new")

                h.sync()

                then("the cached token is evicted and the call is repeated once with the fresh one") {
                    verifyOrder {
                        h.calendarClient.insert(accessToken = "ya29.stale", eventId = mirroredId, event = expectedBody)
                        h.tokenProvider.evict(userId = host)
                        h.calendarClient.insert(accessToken = "ya29.fresh", eventId = mirroredId, event = expectedBody)
                        h.queue.markSynced(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            googleEventId = "evt-new",
                            now = start,
                        )
                    }
                    h.verifyNoRetryOrFailure()
                }
            }

            `when`("the fresh token is rejected as well") {
                val h = Harness()
                h.due(row() to view())
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Unauthorized

                h.sync()

                then("the row backs off after exactly two calls") {
                    verify(exactly = 2) { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) }
                    verify(exactly = 1) {
                        h.queue.retryLater(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            attempts = 1,
                            nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                            lastError = "Google rejected a fresh access token",
                            now = start,
                        )
                    }
                }
            }
        }

        given("a token refresh that Google could not answer") {
            val h = Harness()
            h.due(row() to view())
            every { h.tokenProvider.accessToken(userId = host) } returns
                AccessTokenResult.Unavailable(message = "Google token endpoint returned 503")

            h.sync()

            then("the row backs off with that message and Google Calendar is not called") {
                verify(exactly = 1) {
                    h.queue.retryLater(
                        id = 1L,
                        token = h.token(),
                        observedSeq = 3L,
                        attempts = 1,
                        nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                        lastError = "Google token endpoint returned 503",
                        now = start,
                    )
                }
                h.verifyNoGoogleCall()
            }
        }

        given("Google fails the call") {
            `when`("the rows have fewer attempts than the limit") {
                val h = Harness()
                h.due(
                    row(id = 1L, attempts = 0) to view(),
                    row(id = 2L, attempts = 1) to view(),
                    row(id = 3L, attempts = 2) to view(),
                )
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Failed(statusCode = 500, message = "HTTP 500")

                h.sync()

                then("each is retried with its attempt count and a doubling delay of 1, 2 and 4 minutes") {
                    listOf(1L to 1, 2L to 2, 3L to 3).forEach { (id, attempts) ->
                        verify(exactly = 1) {
                            h.queue.retryLater(
                                id = id,
                                token = any(),
                                observedSeq = 3L,
                                attempts = attempts,
                                nextAttemptAt = start.plus(Duration.ofMinutes(1L shl (attempts - 1))),
                                lastError = "HTTP 500",
                                now = start,
                            )
                        }
                    }
                    h.staged.shouldBeEmpty()
                }
            }

            `when`("the doubling would pass an hour, or wrap around the 64-bit shift") {
                val h = Harness(appConfig = googleConfig(maxAttempts = 100))
                h.due(row(id = 1L, attempts = 9) to view(), row(id = 2L, attempts = 64) to view())
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Failed(statusCode = 500, message = "HTTP 500")

                h.sync()

                then("the delay is capped at 60 minutes") {
                    listOf(1L to 10, 2L to 65).forEach { (id, attempts) ->
                        verify(exactly = 1) {
                            h.queue.retryLater(
                                id = id,
                                token = any(),
                                observedSeq = 3L,
                                attempts = attempts,
                                nextAttemptAt = start.plus(Duration.ofMinutes(60L)),
                                lastError = "HTTP 500",
                                now = start,
                            )
                        }
                    }
                }
            }

            `when`("the row reaches the attempt limit") {
                val h = Harness(transactionManager = createH2TransactionManager())
                h.due(row(attempts = 7) to view(title = "R&D <!here>"))
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Failed(statusCode = 500, message = "Backend Error")

                h.sync()

                then(
                    "it is failed for good with the attempt count it reached, and the user gets one DM with the " +
                        "escaped title and the reason",
                ) {
                    verify(exactly = 1) {
                        h.queue.markFailed(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            attempts = 8,
                            lastError = "Backend Error",
                            now = start,
                        )
                    }
                    verify(exactly = 0) {
                        h.queue.retryLater(
                            id = any(),
                            token = any(),
                            observedSeq = any(),
                            attempts = any(),
                            nextAttemptAt = any(),
                            lastError = any(),
                            now = any(),
                        )
                    }
                    h.directMessages() shouldBe
                        listOf(
                            host to
                                "CodeCompanion couldn't sync *R&amp;D &lt;!here&gt;* to your Google Calendar " +
                                "(Backend Error). It will not retry; reconnect with `/meetup calendar connect` " +
                                "if this keeps happening.",
                        )
                }

                then("the row is failed outside a transaction and the DM is staged in one of its own") {
                    h.failedInsideTransaction shouldBe listOf(false)
                    h.stagedInsideTransaction shouldBe listOf(true)
                }
            }

            `when`("the row reaches the limit but a hook changed the meeting during that last attempt") {
                val h = Harness()
                h.due(row(attempts = 7) to view())
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Failed(statusCode = 500, message = "HTTP 500")
                every {
                    h.queue.markFailed(
                        id = 1L,
                        token = any(),
                        observedSeq = 3L,
                        attempts = any(),
                        lastError = any(),
                        now = any(),
                    )
                } returns CalendarSyncStatus.PENDING

                val errors = h.sync()

                then("the row is requeued by markFailed, so no 'will not retry' DM is sent") {
                    h.staged.shouldBeEmpty()
                    errors.shouldBeEmpty()
                }
            }

            `when`("the row reaches the limit but its claim was lost meanwhile") {
                val h = Harness()
                h.due(row(attempts = 7) to view())
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.Failed(statusCode = 500, message = "HTTP 500")
                every {
                    h.queue.markFailed(
                        id = 1L,
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        lastError = any(),
                        now = any(),
                    )
                } returns null

                h.sync()

                then("no DM is sent") {
                    h.staged.shouldBeEmpty()
                }
            }
        }

        given("Google rate-limits the first of two due rows") {
            listOf(
                null to Duration.ofMinutes(2L),
                Duration.ofSeconds(7L) to Duration.ofMinutes(2L),
                Duration.ofSeconds(600L) to Duration.ofSeconds(600L),
            ).forEach { (retryAfter, expectedDelay) ->
                `when`("Retry-After is $retryAfter") {
                    val h = Harness()
                    h.due(row(id = 1L, attempts = 3) to view(), row(id = 2L, user = guest) to view())
                    every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                        CalendarApiResult.RateLimited(retryAfter = retryAfter)

                    val errors = h.sync()

                    then(
                        "the row waits the longer of the hint and two minutes ($expectedDelay) with its attempt " +
                            "count unchanged",
                    ) {
                        verify(exactly = 1) {
                            h.queue.retryLater(
                                id = 1L,
                                token = any(),
                                observedSeq = 3L,
                                attempts = 3,
                                nextAttemptAt = start.plus(expectedDelay),
                                lastError = CalendarSyncService.RATE_LIMITED_ERROR,
                                now = start,
                            )
                        }
                        CalendarSyncService.RATE_LIMITED_ERROR shouldBe "rate limited by Google"
                        errors.shouldBeEmpty()
                    }

                    then("the tick ends before the next row is claimed") {
                        verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                    }
                }
            }

            `when`("the row is one attempt short of the limit") {
                val h = Harness()
                h.due(row(attempts = 7) to view())
                every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                    CalendarApiResult.RateLimited(retryAfter = null)

                h.sync()

                then(
                    "a rate limit does not count as an attempt: the row is requeued, never failed, and no DM is sent",
                ) {
                    verify(exactly = 1) {
                        h.queue.retryLater(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            attempts = 7,
                            nextAttemptAt = start.plus(Duration.ofMinutes(2L)),
                            lastError = CalendarSyncService.RATE_LIMITED_ERROR,
                            now = start,
                        )
                    }
                    h.verifyNoFailure()
                    h.staged.shouldBeEmpty()
                }
            }
        }

        given("a tick whose first call outlasts the time budget") {
            val clock = MutableClock(current = start, zoneId = zone)
            val h = Harness(clock = clock)
            h.due(row(id = 1L) to view(), row(id = 2L) to view(), row(id = 3L) to view())
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } answers {
                clock.advance(by = Duration.ofSeconds(30L))
                CalendarApiResult.Ok(eventId = "evt-slow")
            }

            h.sync()

            then("the first row finishes and the later rows are left for the next tick") {
                verify(exactly = 1) { h.queue.claim(id = 1L, token = any(), now = any()) }
                verify(exactly = 0) { h.queue.claim(id = 2L, token = any(), now = any()) }
                verify(exactly = 0) { h.queue.claim(id = 3L, token = any(), now = any()) }
            }
        }

        given("a row another worker claimed first") {
            val h = Harness()
            h.due(row() to view())
            every { h.queue.claim(id = 1L, token = any(), now = any()) } returns null

            h.sync()

            then("nothing else happens to it") {
                verify(exactly = 0) { h.queue.find(id = any()) }
                verify(exactly = 0) { h.queue.loadSyncView(meetingId = any()) }
                verify(exactly = 0) { h.tokenProvider.accessToken(userId = any()) }
                h.verifyNoGoogleCall()
            }
        }

        given("a repository failure on one row") {
            val h = Harness()
            h.due(row(id = 1L, meetingId = 7L) to view(), row(id = 2L, meetingId = 8L) to view(meetingId = 8L))
            every { h.queue.loadSyncView(meetingId = 7L) } throws IllegalStateException("db down")
            every { h.calendarClient.insert(accessToken = any(), eventId = any(), event = any()) } returns
                CalendarApiResult.Ok(eventId = "evt-8")

            val errors = h.sync()

            then("one ERROR names the row, meeting and user, and the next row is still synced") {
                errors.size shouldBe 1
                errors.single().formattedMessage shouldContain "rowId=1 meetingId=7 userId=U_HOST"
                errors.single().throwableProxy.message shouldBe "db down"
                verify(exactly = 1) {
                    h.queue.markSynced(id = 2L, token = any(), observedSeq = 3L, googleEventId = "evt-8", now = start)
                }
            }

            then("the failed row backs off like any other failure, with the exception message as its error") {
                verify(exactly = 1) {
                    h.queue.retryLater(
                        id = 1L,
                        token = any(),
                        observedSeq = 3L,
                        attempts = 1,
                        nextAttemptAt = start.plus(Duration.ofMinutes(1L)),
                        lastError = "db down",
                        now = start,
                    )
                }
            }
        }

        given("a row whose meeting view throws on every pass") {
            `when`("it reaches the attempt limit") {
                val h = Harness(transactionManager = createH2TransactionManager())
                h.due(row(attempts = 7) to view())
                every { h.queue.loadSyncView(meetingId = 7L) } throws IllegalStateException("poisoned row")

                val errors = h.sync()

                then("it is failed for good instead of looping, and the user is told about a meeting") {
                    verify(exactly = 1) {
                        h.queue.markFailed(
                            id = 1L,
                            token = h.token(),
                            observedSeq = 3L,
                            attempts = 8,
                            lastError = "poisoned row",
                            now = start,
                        )
                    }
                    h.verifyNoRetry()
                    h.directMessages() shouldBe
                        listOf(
                            host to
                                "CodeCompanion couldn't sync a meeting to your Google Calendar (poisoned row). " +
                                "It will not retry; reconnect with `/meetup calendar connect` if this keeps happening.",
                        )
                    errors.size shouldBe 1
                }
            }

            `when`("scheduling the retry fails as well") {
                val h = Harness()
                h.due(row() to view())
                every { h.queue.loadSyncView(meetingId = 7L) } throws IllegalStateException("db down")
                every {
                    h.queue.retryLater(
                        id = 1L,
                        token = any(),
                        observedSeq = any(),
                        attempts = any(),
                        nextAttemptAt = any(),
                        lastError = any(),
                        now = any(),
                    )
                } throws IllegalStateException("db still down")

                val errors = h.sync()

                then("both failures are logged and contained, and the row is left SYNCING for the stuck sweep") {
                    errors.size shouldBe 2
                    errors[1].formattedMessage shouldContain "could not schedule a retry of a failed row: rowId=1"
                    errors[1].throwableProxy.message shouldBe "db still down"
                    h.verifyNoFailure()
                }
            }
        }

        given("the event id a meeting is mirrored under") {
            then("it is the SHA-256 of `uid:user` in lowercase hex: 64 characters of Google's base32hex alphabet") {
                mirroredEventIdOf(meetingUid = meetingUid, slackUserId = host) shouldBe mirroredId
                mirroredEventIdOf(meetingUid = meetingUid, slackUserId = guest) shouldBe guestMirroredId
                listOf(mirroredId, guestMirroredId).forEach { id ->
                    id shouldMatch Regex(pattern = "[0-9a-v]{5,1024}")
                    id.length shouldBe 64
                }
            }

            then("it is stable per meeting and user, and differs for another user or another meeting") {
                mirroredEventIdOf(meetingUid = meetingUid, slackUserId = host) shouldBe
                    mirroredEventIdOf(meetingUid = meetingUid, slackUserId = host)
                mirroredEventIdOf(meetingUid = meetingUid, slackUserId = guest) shouldNotBe
                    mirroredEventIdOf(meetingUid = meetingUid, slackUserId = host)
                mirroredEventIdOf(
                    meetingUid = UUID.fromString("7d2c3f9e-4b1a-4c55-9a8e-0f6b2d1c3e4b"),
                    slackUserId = host,
                ) shouldNotBe mirroredId
            }
        }

        given("the scheduler") {
            `when`("a whole sync tick fails") {
                val failing = mockk<CalendarSyncService>()
                every { failing.syncDue() } throws IllegalStateException("db down")

                val errors =
                    captureErrorLogs(loggerName = CalendarSyncScheduler::class.java.name) {
                        CalendarSyncScheduler(syncService = failing).tick()
                    }

                then("the failure is logged and does not escape the scheduled method") {
                    errors.single().formattedMessage shouldBe "Google Calendar sync tick failed"
                    errors.single().throwableProxy.message shouldBe "db down"
                }
            }
        }
    })
