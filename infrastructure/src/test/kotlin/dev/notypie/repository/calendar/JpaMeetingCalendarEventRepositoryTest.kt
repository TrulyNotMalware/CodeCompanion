package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarSyncStatus
import dev.notypie.repository.calendar.schema.MeetingCalendarEventSchema
import dev.notypie.repository.meeting.JpaMeetingRepository
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.schema.createCalendarMeetingView
import dev.notypie.schema.createMeetingCalendarEvent
import dev.notypie.schema.createMeetingCalendarEventSchema
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createParticipants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:meeting_calendar_event_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class JpaMeetingCalendarEventRepositoryTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
        private val jpaMeetingCalendarEventRepository: JpaMeetingCalendarEventRepository,
        private val transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                MeetingCalendarEventRepositoryImpl(
                    jpaMeetingRepository = jpaMeetingRepository,
                    jpaMeetingCalendarEventRepository = jpaMeetingCalendarEventRepository,
                    transactionManager = transactionManager,
                )
            val now = Instant.parse("2031-06-01T09:00:00Z")
            afterSpec { jpaMeetingCalendarEventRepository.deleteAllInBatch() }

            fun persistMeeting(publisherId: String, startAt: LocalDateTime = LocalDateTime.now()): MeetingSchema =
                jpaMeetingRepository.save(createMeetingSchema(publisherId = publisherId, startAt = startAt))

            fun rowOrNull(meeting: MeetingSchema, userId: String): MeetingCalendarEventSchema? =
                jpaMeetingCalendarEventRepository
                    .findAll()
                    .singleOrNull { it.meeting.id == meeting.id && it.slackUserId == userId }

            fun rowOf(meeting: MeetingSchema, userId: String): MeetingCalendarEventSchema =
                checkNotNull(rowOrNull(meeting = meeting, userId = userId)) {
                    "no calendar row for meeting ${meeting.id} and $userId"
                }

            fun enqueued(meeting: MeetingSchema, userId: String, at: Instant = now): Long {
                repository.enqueue(meetingId = meeting.id, slackUserId = userId, now = at)
                return rowOf(meeting = meeting, userId = userId).id
            }

            fun claimedSeq(id: Long, token: String, at: Instant = now): Long =
                checkNotNull(repository.claim(id = id, token = token, now = at)) { "claim of row $id missed" }.changeSeq

            given("a host enqueued for a meeting whose row later failed") {
                val meeting = persistMeeting(publisherId = "U_ENQ")
                repository.enqueue(meetingId = meeting.id, slackUserId = "U_ENQ", now = now)
                val initial = rowOf(meeting = meeting, userId = "U_ENQ")
                val id = initial.id
                claimedSeq(id = id, token = "enq-1") shouldBe 1L
                repository.retryLater(
                    id = id,
                    token = "enq-1",
                    observedSeq = 1L,
                    attempts = 2,
                    nextAttemptAt = now.plusSeconds(60L),
                    lastError = "HTTP 500",
                    now = now,
                ) shouldBe true
                claimedSeq(id = id, token = "enq-2", at = now.plusSeconds(60L)) shouldBe 1L
                repository.markFailed(
                    id = id,
                    token = "enq-2",
                    observedSeq = 1L,
                    attempts = 2,
                    lastError = "gave up",
                    now = now.plusSeconds(60L),
                ) shouldBe CalendarSyncStatus.FAILED
                val failed = rowOf(meeting = meeting, userId = "U_ENQ")

                `when`("the same pair is enqueued again") {
                    repository.enqueue(meetingId = meeting.id, slackUserId = "U_ENQ", now = now.plusSeconds(120L))

                    then(
                        "the first call inserted a PENDING row at change_seq 1, due at once, stamped in the JVM zone",
                    ) {
                        initial.status shouldBe CalendarSyncStatus.PENDING
                        initial.changeSeq shouldBe 1L
                        initial.attempts shouldBe 0
                        initial.nextAttemptAt shouldBe now
                        initial.googleEventId.shouldBeNull()
                        initial.createdAt shouldBe LocalDateTime.ofInstant(now, ZoneId.systemDefault())
                        initial.updatedAt shouldBe LocalDateTime.ofInstant(now, ZoneId.systemDefault())
                    }

                    then(
                        "the duplicate key updated that row: seq 2, back to PENDING, attempts reset, due at its time",
                    ) {
                        failed.status shouldBe CalendarSyncStatus.FAILED
                        failed.attempts shouldBe 2
                        repository.find(id = id) shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_ENQ",
                                status = CalendarSyncStatus.PENDING,
                                changeSeq = 2L,
                                attempts = 0,
                                nextAttemptAt = now.plusSeconds(120L),
                                lastError = "gave up",
                            )
                        val row = rowOf(meeting = meeting, userId = "U_ENQ")
                        row.createdAt shouldBe initial.createdAt
                        row.updatedAt shouldBe LocalDateTime.ofInstant(now.plusSeconds(120L), ZoneId.systemDefault())
                    }
                }
            }

            given("a row a worker holds when its pair is enqueued again") {
                val meeting = persistMeeting(publisherId = "U_ENQ_SYNC")
                val id = enqueued(meeting = meeting, userId = "U_ENQ_SYNC")
                claimedSeq(id = id, token = "enq-sync") shouldBe 1L
                val claimed = rowOf(meeting = meeting, userId = "U_ENQ_SYNC")

                `when`("the upsert meets the existing key") {
                    repository.enqueue(meetingId = meeting.id, slackUserId = "U_ENQ_SYNC", now = now.plusSeconds(30L))

                    then("change_seq is bumped while the status, the claim and updated_at stay with the worker") {
                        claimed.updatedAt.shouldNotBeNull()
                        val row = rowOf(meeting = meeting, userId = "U_ENQ_SYNC")
                        row.changeSeq shouldBe 2L
                        row.status shouldBe CalendarSyncStatus.SYNCING
                        row.claimToken shouldBe "enq-sync"
                        row.updatedAt shouldBe claimed.updatedAt
                        row.attempts shouldBe 0
                        row.nextAttemptAt shouldBe now.plusSeconds(30L)
                        jpaMeetingCalendarEventRepository
                            .findAll()
                            .count { it.meeting.id == meeting.id } shouldBe 1
                    }
                }
            }

            given("a pair that already has a row") {
                val meeting = persistMeeting(publisherId = "U_DUP")
                enqueued(meeting = meeting, userId = "U_DUP")

                `when`("a second row for the same pair is inserted directly") {
                    val failure =
                        shouldThrow<DataIntegrityViolationException> {
                            jpaMeetingCalendarEventRepository.saveAndFlush(
                                createMeetingCalendarEventSchema(
                                    meeting = meeting,
                                    slackUserId = "U_DUP",
                                    nextAttemptAt = now,
                                ),
                            )
                        }

                    then("the unique key the enqueue upsert relies on rejects it") {
                        failure.mostSpecificCause.message.orEmpty() shouldContainIgnoringCase
                            "uk_meeting_calendar_event_meeting_user"
                    }
                }
            }

            given("a PENDING row due at a given time") {
                val meeting = persistMeeting(publisherId = "U_CLAIM")
                val id = enqueued(meeting = meeting, userId = "U_CLAIM")

                `when`("it is claimed before it is due, then when due, then replayed") {
                    val early = repository.claim(id = id, token = "claim-early", now = now.minusSeconds(1L))
                    val first = repository.claim(id = id, token = "claim-1", now = now)
                    val replay = repository.claim(id = id, token = "claim-2", now = now.plusSeconds(1L))

                    then("only the claim at or after next_attempt_at wins, once, and returns the claimed row") {
                        early.shouldBeNull()
                        replay.shouldBeNull()
                        first shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_CLAIM",
                                status = CalendarSyncStatus.SYNCING,
                                changeSeq = 1L,
                                nextAttemptAt = now,
                            )
                        rowOf(meeting = meeting, userId = "U_CLAIM").claimToken shouldBe "claim-1"
                    }
                }
            }

            given("a row touched before it is claimed") {
                val meeting = persistMeeting(publisherId = "U_SNAP")
                val id = enqueued(meeting = meeting, userId = "U_SNAP")
                repository.touchOne(meetingId = meeting.id, slackUserId = "U_SNAP", now = now) shouldBe true

                `when`("it is claimed") {
                    val claimed = repository.claim(id = id, token = "snap-1", now = now)

                    then("the snapshot carries the change_seq the claim saw") {
                        claimed.shouldNotBeNull().changeSeq shouldBe 2L
                    }
                }
            }

            given("a claimed row whose meeting has not changed since the claim") {
                val meeting = persistMeeting(publisherId = "U_SYNC")
                val id = enqueued(meeting = meeting, userId = "U_SYNC")
                val observed = claimedSeq(id = id, token = "sync-1")

                `when`("a foreign token completes it, then the owner does") {
                    val foreign =
                        repository.markSynced(
                            id = id,
                            token = "sync-other",
                            observedSeq = observed,
                            googleEventId = "evt-foreign",
                            now = now.plusSeconds(5L),
                        )
                    val owner =
                        repository.markSynced(
                            id = id,
                            token = "sync-1",
                            observedSeq = observed,
                            googleEventId = "evt-1",
                            now = now.plusSeconds(5L),
                        )

                    then("the foreign completion matches nothing and the owner's lands as SYNCED with the event id") {
                        foreign shouldBe false
                        owner shouldBe true
                        repository.find(id = id) shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_SYNC",
                                googleEventId = "evt-1",
                                status = CalendarSyncStatus.SYNCED,
                                changeSeq = 1L,
                                nextAttemptAt = now.plusSeconds(5L),
                            )
                        rowOf(meeting = meeting, userId = "U_SYNC").claimToken.shouldBeNull()
                    }
                }
            }

            given("a claimed row whose meeting changes while the Google call is in flight") {
                val meeting = persistMeeting(publisherId = "U_MOVE")
                val id = enqueued(meeting = meeting, userId = "U_MOVE")
                val observed = claimedSeq(id = id, token = "move-1")
                val touched = repository.touchMeeting(meetingId = meeting.id, now = now.plusSeconds(1L))
                val inFlight = rowOf(meeting = meeting, userId = "U_MOVE")

                `when`("the worker completes with the seq it observed at the claim") {
                    val completed =
                        repository.markSynced(
                            id = id,
                            token = "move-1",
                            observedSeq = observed,
                            googleEventId = "evt-created",
                            now = now.plusSeconds(2L),
                        )

                    then("touchMeeting bumped the seq but left the SYNCING claim in place") {
                        touched shouldBe 1
                        inFlight.status shouldBe CalendarSyncStatus.SYNCING
                        inFlight.changeSeq shouldBe 2L
                        inFlight.claimToken shouldBe "move-1"
                    }

                    then("the row goes back to PENDING, due at once, and still stores the event the call created") {
                        completed shouldBe true
                        repository.find(id = id) shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_MOVE",
                                googleEventId = "evt-created",
                                status = CalendarSyncStatus.PENDING,
                                changeSeq = 2L,
                                nextAttemptAt = now.plusSeconds(2L),
                            )
                    }
                }

                `when`("the next pass claims it and completes with the new seq") {
                    val reclaimed = claimedSeq(id = id, token = "move-2", at = now.plusSeconds(3L))
                    val settled =
                        repository.markSynced(
                            id = id,
                            token = "move-2",
                            observedSeq = reclaimed,
                            googleEventId = "evt-created",
                            now = now.plusSeconds(4L),
                        )

                    then("it settles as SYNCED on the same event") {
                        reclaimed shouldBe 2L
                        settled shouldBe true
                        val row = rowOf(meeting = meeting, userId = "U_MOVE")
                        row.status shouldBe CalendarSyncStatus.SYNCED
                        row.googleEventId shouldBe "evt-created"
                    }
                }
            }

            given("a synced event the worker deletes after the meeting changed") {
                val meeting = persistMeeting(publisherId = "U_DEL")
                val id = enqueued(meeting = meeting, userId = "U_DEL")
                claimedSeq(id = id, token = "del-1") shouldBe 1L
                repository.markSynced(
                    id = id,
                    token = "del-1",
                    observedSeq = 1L,
                    googleEventId = "evt-del",
                    now = now,
                ) shouldBe true
                repository.touchOne(meetingId = meeting.id, slackUserId = "U_DEL", now = now.plusSeconds(1L)) shouldBe
                    true
                claimedSeq(id = id, token = "del-2", at = now.plusSeconds(1L)) shouldBe 2L

                `when`("the delete completes with a stale seq, a foreign token, then the claimed seq") {
                    val stale = repository.deleteSynced(id = id, token = "del-2", observedSeq = 1L)
                    val foreign = repository.deleteSynced(id = id, token = "del-other", observedSeq = 2L)
                    val current = repository.deleteSynced(id = id, token = "del-2", observedSeq = 2L)

                    then("only the matching token and seq remove the row") {
                        stale shouldBe false
                        foreign shouldBe false
                        current shouldBe true
                        repository.find(id = id).shouldBeNull()
                    }
                }
            }

            given("a delete whose row is touched again while Google deletes the event") {
                val meeting = persistMeeting(publisherId = "U_REL")
                val id = enqueued(meeting = meeting, userId = "U_REL")
                claimedSeq(id = id, token = "rel-1") shouldBe 1L
                repository.markSynced(
                    id = id,
                    token = "rel-1",
                    observedSeq = 1L,
                    googleEventId = "evt-rel",
                    now = now,
                ) shouldBe true
                repository.touchOne(meetingId = meeting.id, slackUserId = "U_REL", now = now.plusSeconds(1L)) shouldBe
                    true
                claimedSeq(id = id, token = "rel-2", at = now.plusSeconds(1L)) shouldBe 2L
                repository.touchOne(meetingId = meeting.id, slackUserId = "U_REL", now = now.plusSeconds(2L)) shouldBe
                    true

                `when`("deleteSynced misses on the seq and the worker releases the row") {
                    val deleted = repository.deleteSynced(id = id, token = "rel-2", observedSeq = 2L)
                    val foreign =
                        repository.releaseWithoutEvent(id = id, token = "rel-other", now = now.plusSeconds(3L))
                    val released = repository.releaseWithoutEvent(id = id, token = "rel-2", now = now.plusSeconds(3L))

                    then("the row stays, goes back to PENDING due at once and forgets the deleted event id") {
                        deleted shouldBe false
                        foreign shouldBe false
                        released shouldBe true
                        repository.find(id = id) shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_REL",
                                status = CalendarSyncStatus.PENDING,
                                changeSeq = 3L,
                                nextAttemptAt = now.plusSeconds(3L),
                            )
                        rowOf(meeting = meeting, userId = "U_REL").claimToken.shouldBeNull()
                    }
                }
            }

            given("a row that is not SYNCING but still names a claim token") {
                val meeting = persistMeeting(publisherId = "U_REL_STATUS")
                val stored =
                    jpaMeetingCalendarEventRepository.saveAndFlush(
                        createMeetingCalendarEventSchema(
                            meeting = meeting,
                            slackUserId = "U_REL_STATUS",
                            status = CalendarSyncStatus.PENDING,
                            claimToken = "rel-status",
                            nextAttemptAt = now,
                        ),
                    )

                `when`("the holder of that token releases it") {
                    val released =
                        repository.releaseWithoutEvent(id = stored.id, token = "rel-status", now = now.plusSeconds(9L))

                    then("the status guard refuses it and the row is unchanged") {
                        released shouldBe false
                        val row = rowOf(meeting = meeting, userId = "U_REL_STATUS")
                        row.status shouldBe CalendarSyncStatus.PENDING
                        row.claimToken shouldBe "rel-status"
                        row.nextAttemptAt shouldBe now
                    }
                }
            }

            given("a claimed row whose Google call failed") {
                val meeting = persistMeeting(publisherId = "U_RETRY")
                val id = enqueued(meeting = meeting, userId = "U_RETRY")
                claimedSeq(id = id, token = "retry-1") shouldBe 1L
                val backoff = now.plusSeconds(300L)

                `when`("a foreign token reports the failure, then the owner schedules a retry") {
                    val foreignRetry =
                        repository.retryLater(
                            id = id,
                            token = "retry-other",
                            observedSeq = 1L,
                            attempts = 1,
                            nextAttemptAt = backoff,
                            lastError = "HTTP 500",
                            now = now,
                        )
                    val foreignFail =
                        repository.markFailed(
                            id = id,
                            token = "retry-other",
                            observedSeq = 1L,
                            attempts = 1,
                            lastError = "x",
                            now = now,
                        )
                    val retried =
                        repository.retryLater(
                            id = id,
                            token = "retry-1",
                            observedSeq = 1L,
                            attempts = 1,
                            nextAttemptAt = backoff,
                            lastError = "HTTP 500",
                            now = now,
                        )
                    val replayed =
                        repository.retryLater(
                            id = id,
                            token = "retry-1",
                            observedSeq = 1L,
                            attempts = 2,
                            nextAttemptAt = backoff.plusSeconds(300L),
                            lastError = "HTTP 500",
                            now = now,
                        )
                    val tooEarly = repository.claim(id = id, token = "retry-2", now = backoff.minusSeconds(1L))

                    then("only the owner's retry lands: PENDING with the attempt count, the backoff and the error") {
                        foreignRetry shouldBe false
                        foreignFail.shouldBeNull()
                        retried shouldBe true
                        replayed shouldBe false
                        tooEarly.shouldBeNull()
                        repository.find(id = id) shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_RETRY",
                                status = CalendarSyncStatus.PENDING,
                                attempts = 1,
                                nextAttemptAt = backoff,
                                lastError = "HTTP 500",
                            )
                        rowOf(meeting = meeting, userId = "U_RETRY").claimToken.shouldBeNull()
                    }
                }

                `when`("the retry is claimed when due and the worker gives up") {
                    val reclaimed = claimedSeq(id = id, token = "retry-2", at = backoff)
                    val failed =
                        repository.markFailed(
                            id = id,
                            token = "retry-2",
                            observedSeq = reclaimed,
                            attempts = 2,
                            lastError = "gave up",
                            now = backoff,
                        )
                    val failedAgain =
                        repository.markFailed(
                            id = id,
                            token = "retry-2",
                            observedSeq = reclaimed,
                            attempts = 2,
                            lastError = "gave up",
                            now = backoff,
                        )

                    then(
                        "the row is FAILED with the reason and the attempt count the worker reached, and a second " +
                            "report matches nothing",
                    ) {
                        failed shouldBe CalendarSyncStatus.FAILED
                        failedAgain.shouldBeNull()
                        repository.find(id = id) shouldBe
                            createMeetingCalendarEvent(
                                id = id,
                                meetingId = meeting.id,
                                slackUserId = "U_RETRY",
                                status = CalendarSyncStatus.FAILED,
                                attempts = 2,
                                nextAttemptAt = backoff,
                                lastError = "gave up",
                            )
                        rowOf(meeting = meeting, userId = "U_RETRY").claimToken.shouldBeNull()
                    }
                }
            }

            given("claimed rows that a hook touches while their Google calls fail") {
                val meeting = persistMeeting(publisherId = "U_SEQ")
                val failId = enqueued(meeting = meeting, userId = "U_SEQ_FAIL")
                val retryId = enqueued(meeting = meeting, userId = "U_SEQ_RETRY")
                val failSeq = claimedSeq(id = failId, token = "seq-fail")
                val retrySeq = claimedSeq(id = retryId, token = "seq-retry")
                repository.touchMeeting(meetingId = meeting.id, now = now.plusSeconds(1L)) shouldBe 2

                `when`("the owners report the failure with the seq they claimed") {
                    val failed =
                        repository.markFailed(
                            id = failId,
                            token = "seq-fail",
                            observedSeq = failSeq,
                            attempts = 8,
                            lastError = "gave up",
                            now = now.plusSeconds(5L),
                        )
                    val retried =
                        repository.retryLater(
                            id = retryId,
                            token = "seq-retry",
                            observedSeq = retrySeq,
                            attempts = 3,
                            nextAttemptAt = now.plusSeconds(600L),
                            lastError = "HTTP 500",
                            now = now.plusSeconds(5L),
                        )

                    then("markFailed leaves the row PENDING, attempts 0 and due at once instead of FAILED") {
                        failed shouldBe CalendarSyncStatus.PENDING
                        repository.find(id = failId) shouldBe
                            createMeetingCalendarEvent(
                                id = failId,
                                meetingId = meeting.id,
                                slackUserId = "U_SEQ_FAIL",
                                status = CalendarSyncStatus.PENDING,
                                changeSeq = 2L,
                                nextAttemptAt = now.plusSeconds(5L),
                                lastError = "gave up",
                            )
                        rowOf(meeting = meeting, userId = "U_SEQ_FAIL").claimToken.shouldBeNull()
                    }

                    then("retryLater ignores its backoff and attempt count: PENDING, attempts 0, due at once") {
                        retried shouldBe true
                        repository.find(id = retryId) shouldBe
                            createMeetingCalendarEvent(
                                id = retryId,
                                meetingId = meeting.id,
                                slackUserId = "U_SEQ_RETRY",
                                status = CalendarSyncStatus.PENDING,
                                changeSeq = 2L,
                                nextAttemptAt = now.plusSeconds(5L),
                                lastError = "HTTP 500",
                            )
                    }
                }
            }

            given("one row claimed at an application time long past and one claimed just now") {
                val claimedAt = Instant.parse("2020-01-01T00:00:00Z")
                val old = persistMeeting(publisherId = "U_STUCK_OLD")
                val oldId = enqueued(meeting = old, userId = "U_STUCK_OLD", at = claimedAt)
                claimedSeq(id = oldId, token = "stuck-old", at = claimedAt) shouldBe 1L
                val fresh = persistMeeting(publisherId = "U_STUCK_NEW")
                val freshId = enqueued(meeting = fresh, userId = "U_STUCK_NEW")
                claimedSeq(id = freshId, token = "stuck-new") shouldBe 1L

                `when`("the stuck sweep runs with cutoffs on either side of the old claim") {
                    val before = repository.resetStuck(olderThan = claimedAt.minusSeconds(60L), now = now)
                    val after = repository.resetStuck(olderThan = claimedAt.plusSeconds(60L), now = now)
                    val lateOwner =
                        repository.markSynced(
                            id = oldId,
                            token = "stuck-old",
                            observedSeq = 1L,
                            googleEventId = "evt-late",
                            now = now,
                        )

                    then("only the cutoff after the old claim resets it: the sweep compares against the bound time") {
                        before shouldBe 0
                        after shouldBe 1
                        val reset = rowOf(meeting = old, userId = "U_STUCK_OLD")
                        reset.status shouldBe CalendarSyncStatus.PENDING
                        reset.claimToken.shouldBeNull()
                        val kept = rowOf(meeting = fresh, userId = "U_STUCK_NEW")
                        kept.status shouldBe CalendarSyncStatus.SYNCING
                        kept.claimToken shouldBe "stuck-new"
                    }

                    then("the original owner's completion misses once the sweep cleared its token") {
                        lateOwner shouldBe false
                        rowOf(meeting = old, userId = "U_STUCK_OLD").googleEventId.shouldBeNull()
                    }
                }
            }

            given("rows claimed long ago whose meetings hooks touch while their workers are stuck") {
                val claimedAt = Instant.parse("2020-02-01T00:00:00Z")
                val byId = persistMeeting(publisherId = "U_N2_ID")
                val byUid = persistMeeting(publisherId = "U_N2_UID")
                val single = persistMeeting(publisherId = "U_N2_ONE")
                val upserted = persistMeeting(publisherId = "U_N2_UPSERT")
                val byUser = persistMeeting(publisherId = "U_N2_USER")
                val targets =
                    listOf(
                        byId to "U_N2_ID",
                        byUid to "U_N2_UID",
                        single to "U_N2_ONE",
                        upserted to "U_N2_UPSERT",
                        byUser to "U_N2_USER",
                    )
                targets.forEach { (meeting, userId) ->
                    val id = enqueued(meeting = meeting, userId = userId, at = claimedAt)
                    claimedSeq(id = id, token = "n2-$userId", at = claimedAt) shouldBe 1L
                }
                repository.touchMeeting(meetingId = byId.id, now = now) shouldBe 1
                repository.touchMeetingByUid(meetingUid = byUid.meetingUid, now = now) shouldBe 1
                repository.touchOne(meetingId = single.id, slackUserId = "U_N2_ONE", now = now) shouldBe true
                repository.enqueue(meetingId = upserted.id, slackUserId = "U_N2_UPSERT", now = now)
                repository.touchForUser(userId = "U_N2_USER", meetingIds = listOf(byUser.id), now = now) shouldBe 1

                `when`("the stuck sweep runs with a cutoff just after the old claims") {
                    val reset = repository.resetStuck(olderThan = claimedAt.plusSeconds(60L), now = now)

                    then("every touched row is still reset: a touch leaves a SYNCING row's updated_at alone") {
                        reset shouldBe 5
                        targets.forEach { (meeting, userId) ->
                            val row = rowOf(meeting = meeting, userId = userId)
                            row.status shouldBe CalendarSyncStatus.PENDING
                            row.claimToken.shouldBeNull()
                            row.changeSeq shouldBe 2L
                        }
                    }
                }
            }

            given("two meetings with queued rows") {
                val target = persistMeeting(publisherId = "U_UID_HOST")
                val other = persistMeeting(publisherId = "U_UID_OTHER")
                enqueued(meeting = target, userId = "U_UID_HOST")
                enqueued(meeting = target, userId = "U_UID_GUEST")
                enqueued(meeting = other, userId = "U_UID_OTHER")

                `when`("rows are touched by the target's uid, and by an unknown uid") {
                    val touched =
                        repository.touchMeetingByUid(meetingUid = target.meetingUid, now = now.plusSeconds(60L))
                    val unknown =
                        repository.touchMeetingByUid(meetingUid = UUID.randomUUID(), now = now.plusSeconds(60L))

                    then("both rows of the target are bumped through the uid subselect; the other meeting is not") {
                        touched shouldBe 2
                        unknown shouldBe 0
                        listOf("U_UID_HOST", "U_UID_GUEST").forEach { userId ->
                            val row = rowOf(meeting = target, userId = userId)
                            row.changeSeq shouldBe 2L
                            row.nextAttemptAt shouldBe now.plusSeconds(60L)
                        }
                        val untouched = rowOf(meeting = other, userId = "U_UID_OTHER")
                        untouched.changeSeq shouldBe 1L
                        untouched.nextAttemptAt shouldBe now
                    }
                }
            }

            given("one user's rows in every live state next to another user's row") {
                val first = persistMeeting(publisherId = "U_GONE")
                val second = persistMeeting(publisherId = "U_GONE")
                val third = persistMeeting(publisherId = "U_GONE")
                enqueued(meeting = first, userId = "U_GONE")
                enqueued(meeting = first, userId = "U_KEEP")
                val syncingId = enqueued(meeting = second, userId = "U_GONE")
                claimedSeq(id = syncingId, token = "gone-1") shouldBe 1L
                val syncedId = enqueued(meeting = third, userId = "U_GONE")
                claimedSeq(id = syncedId, token = "gone-2") shouldBe 1L
                repository.markSynced(
                    id = syncedId,
                    token = "gone-2",
                    observedSeq = 1L,
                    googleEventId = "evt-gone",
                    now = now,
                ) shouldBe true

                `when`("that user's pending work is failed") {
                    val failed =
                        repository.failPendingForUser(
                            userId = "U_GONE",
                            lastError = "invalid_grant",
                            now = now.plusSeconds(10L),
                        )
                    val inFlight = rowOf(meeting = second, userId = "U_GONE")
                    val ownerCompletion =
                        repository.markSynced(
                            id = syncingId,
                            token = "gone-1",
                            observedSeq = 1L,
                            googleEventId = "evt-late",
                            now = now.plusSeconds(11L),
                        )

                    then("only the PENDING row is FAILED; the SYNCED row and the other user's row are untouched") {
                        failed shouldBe 1
                        val pending = rowOf(meeting = first, userId = "U_GONE")
                        pending.status shouldBe CalendarSyncStatus.FAILED
                        pending.lastError shouldBe "invalid_grant"
                        rowOf(meeting = third, userId = "U_GONE").status shouldBe CalendarSyncStatus.SYNCED
                        rowOf(meeting = first, userId = "U_KEEP").status shouldBe CalendarSyncStatus.PENDING
                    }

                    then("the SYNCING row keeps its claim, so its owner records its own outcome") {
                        inFlight.status shouldBe CalendarSyncStatus.SYNCING
                        inFlight.claimToken shouldBe "gone-1"
                        ownerCompletion shouldBe true
                        val settled = rowOf(meeting = second, userId = "U_GONE")
                        settled.status shouldBe CalendarSyncStatus.SYNCED
                        settled.googleEventId shouldBe "evt-late"
                    }
                }
            }

            given("one user's rows in every state, a failed row of an unlisted meeting, and another user's row") {
                val origin = Instant.parse("2031-08-01T00:00:00Z")
                val startAt = LocalDateTime.ofInstant(origin, ZoneId.systemDefault()).plusDays(1L)
                val meetings = List(4) { persistMeeting(publisherId = "U_BACK", startAt = startAt) }
                val (pendingMeeting, syncingMeeting, syncedMeeting, failedMeeting) = meetings
                val unlistedMeeting = persistMeeting(publisherId = "U_BACK", startAt = startAt)
                val unlistedId = enqueued(meeting = unlistedMeeting, userId = "U_BACK", at = origin)
                claimedSeq(id = unlistedId, token = "back-unlisted", at = origin) shouldBe 1L
                repository.markFailed(
                    id = unlistedId,
                    token = "back-unlisted",
                    observedSeq = 1L,
                    attempts = 8,
                    lastError = "disconnected",
                    now = origin,
                ) shouldBe CalendarSyncStatus.FAILED
                val unlistedBefore = rowOf(meeting = unlistedMeeting, userId = "U_BACK")
                enqueued(meeting = pendingMeeting, userId = "U_BACK", at = origin)
                enqueued(meeting = pendingMeeting, userId = "U_BACK_OTHER", at = origin)
                val syncingId = enqueued(meeting = syncingMeeting, userId = "U_BACK", at = origin)
                claimedSeq(id = syncingId, token = "back-syncing", at = origin) shouldBe 1L
                val syncedId = enqueued(meeting = syncedMeeting, userId = "U_BACK", at = origin)
                claimedSeq(id = syncedId, token = "back-synced", at = origin) shouldBe 1L
                repository.markSynced(
                    id = syncedId,
                    token = "back-synced",
                    observedSeq = 1L,
                    googleEventId = "evt-back",
                    now = origin,
                ) shouldBe true
                val failedId = enqueued(meeting = failedMeeting, userId = "U_BACK", at = origin)
                claimedSeq(id = failedId, token = "back-failed", at = origin) shouldBe 1L
                repository.markFailed(
                    id = failedId,
                    token = "back-failed",
                    observedSeq = 1L,
                    attempts = 8,
                    lastError = "disconnected",
                    now = origin,
                ) shouldBe CalendarSyncStatus.FAILED
                val before = meetings.associateWith { meeting -> rowOf(meeting = meeting, userId = "U_BACK").updatedAt }
                val at = origin.plusSeconds(600L)

                `when`("a reconnect revives that user's rows of the listed meetings") {
                    val touched =
                        repository.touchForUser(
                            userId = "U_BACK",
                            meetingIds = meetings.map { meeting -> meeting.id },
                            now = at,
                        )

                    then("all four rows are bumped; the PENDING, SYNCED and FAILED ones are PENDING, attempts 0, due") {
                        touched shouldBe 4
                        listOf(pendingMeeting, syncedMeeting, failedMeeting).forEach { meeting ->
                            val row = rowOf(meeting = meeting, userId = "U_BACK")
                            row.status shouldBe CalendarSyncStatus.PENDING
                            row.changeSeq shouldBe 2L
                            row.attempts shouldBe 0
                            row.nextAttemptAt shouldBe at
                            row.updatedAt shouldNotBe before.getValue(meeting)
                        }
                        rowOf(meeting = syncedMeeting, userId = "U_BACK").googleEventId shouldBe "evt-back"
                        rowOf(meeting = failedMeeting, userId = "U_BACK").lastError shouldBe "disconnected"
                    }

                    then("the SYNCING row keeps its status, claim and updated_at; only its seq moves") {
                        val row = rowOf(meeting = syncingMeeting, userId = "U_BACK")
                        row.status shouldBe CalendarSyncStatus.SYNCING
                        row.claimToken shouldBe "back-syncing"
                        row.updatedAt shouldBe before.getValue(syncingMeeting)
                        row.changeSeq shouldBe 2L
                    }

                    then("another user's row is untouched") {
                        val other = rowOf(meeting = pendingMeeting, userId = "U_BACK_OTHER")
                        other.changeSeq shouldBe 1L
                        other.nextAttemptAt shouldBe origin
                    }

                    then("the user's row of a meeting missing from the list stays FAILED and unbumped") {
                        val row = rowOf(meeting = unlistedMeeting, userId = "U_BACK")
                        row.status shouldBe CalendarSyncStatus.FAILED
                        row.changeSeq shouldBe 1L
                        row.attempts shouldBe 8
                        row.nextAttemptAt shouldBe origin
                        row.updatedAt shouldBe unlistedBefore.updatedAt
                    }
                }
            }

            given("a revive with no meeting ids") {
                val strict = mockk<JpaMeetingCalendarEventRepository>()
                val guarded =
                    MeetingCalendarEventRepositoryImpl(
                        jpaMeetingRepository = jpaMeetingRepository,
                        jpaMeetingCalendarEventRepository = strict,
                        transactionManager = transactionManager,
                    )

                `when`("touchForUser runs with an empty list") {
                    val touched = guarded.touchForUser(userId = "U_BACK_EMPTY", meetingIds = emptyList(), now = now)

                    then("it returns 0 without sending a statement, since an empty IN list is engine-dependent") {
                        touched shouldBe 0
                        verify { strict wasNot Called }
                    }
                }
            }

            given("PENDING rows due at different times, one synced row and one row not due yet") {
                val origin = Instant.parse("2001-01-01T00:00:00Z")
                val meeting = persistMeeting(publisherId = "U_DUE")
                val late = enqueued(meeting = meeting, userId = "U_DUE_C", at = origin.plusSeconds(30L))
                val early = enqueued(meeting = meeting, userId = "U_DUE_A", at = origin.plusSeconds(10L))
                val tiedFirst = enqueued(meeting = meeting, userId = "U_DUE_B1", at = origin.plusSeconds(20L))
                val tiedSecond = enqueued(meeting = meeting, userId = "U_DUE_B2", at = origin.plusSeconds(20L))
                val synced = enqueued(meeting = meeting, userId = "U_DUE_SYNCED", at = origin.plusSeconds(5L))
                claimedSeq(id = synced, token = "due-1", at = origin.plusSeconds(5L)) shouldBe 1L
                repository.markSynced(
                    id = synced,
                    token = "due-1",
                    observedSeq = 1L,
                    googleEventId = "evt-due",
                    now = origin.plusSeconds(5L),
                ) shouldBe true
                enqueued(meeting = meeting, userId = "U_DUE_LATER", at = origin.plusSeconds(3_600L))

                then("findDue returns the due PENDING rows earliest first, ties by id, up to the limit") {
                    repository.findDue(now = origin.plusSeconds(40L), limit = 10) shouldContainExactly
                        listOf(early, tiedFirst, tiedSecond, late)
                    repository.findDue(now = origin.plusSeconds(40L), limit = 2) shouldContainExactly
                        listOf(early, tiedFirst)
                }
            }

            given("worker transitions called inside a caller's transaction") {
                val untouched = mockk<JpaMeetingCalendarEventRepository>()
                val guarded =
                    MeetingCalendarEventRepositoryImpl(
                        jpaMeetingRepository = jpaMeetingRepository,
                        jpaMeetingCalendarEventRepository = untouched,
                        transactionManager = transactionManager,
                    )
                val calls: List<Pair<String, () -> Any?>> =
                    listOf(
                        "claim" to { guarded.claim(id = 1L, token = "joined", now = now) },
                        "markSynced" to {
                            guarded.markSynced(
                                id = 1L,
                                token = "joined",
                                observedSeq = 1L,
                                googleEventId = "evt",
                                now = now,
                            )
                        },
                        "deleteSynced" to { guarded.deleteSynced(id = 1L, token = "joined", observedSeq = 1L) },
                        "releaseWithoutEvent" to { guarded.releaseWithoutEvent(id = 1L, token = "joined", now = now) },
                        "retryLater" to {
                            guarded.retryLater(
                                id = 1L,
                                token = "joined",
                                observedSeq = 1L,
                                attempts = 1,
                                nextAttemptAt = now,
                                lastError = "x",
                                now = now,
                            )
                        },
                        "markFailed" to {
                            guarded.markFailed(
                                id = 1L,
                                token = "joined",
                                observedSeq = 1L,
                                attempts = 1,
                                lastError = "x",
                                now = now,
                            )
                        },
                        "resetStuck" to { guarded.resetStuck(olderThan = now, now = now) },
                    )

                `when`("each one runs inside TransactionTemplate.execute") {
                    val failures =
                        calls.map { (operation, call) ->
                            operation to
                                shouldThrow<IllegalStateException> {
                                    TransactionTemplate(transactionManager).execute { call() }
                                }
                        }

                    then("each fails fast, naming itself, before any statement reaches the repository") {
                        failures.forEach { (operation, failure) ->
                            failure.message.orEmpty() shouldContain
                                "MeetingCalendarEventRepository.$operation must run outside a transaction"
                        }
                        verify { untouched wasNot Called }
                    }
                }
            }

            given("meetings the sync worker reads") {
                val planned =
                    createMeetingSchema(
                        publisherId = "U_VIEW_HOST",
                        name = "Planning",
                        startAt = LocalDateTime.of(2031, 7, 1, 10, 0),
                        endAt = LocalDateTime.of(2031, 7, 1, 11, 30),
                        reason = "Quarterly planning",
                    )
                listOf("U_VIEW_A", "U_VIEW_B").forEach {
                    planned.participants.add(createParticipants(meeting = planned, userId = it))
                }
                planned.participants.add(
                    createParticipants(meeting = planned, userId = "U_VIEW_NO", isAttending = false),
                )
                val withParticipants = jpaMeetingRepository.save(planned)
                val canceled =
                    jpaMeetingRepository.save(
                        createMeetingSchema(
                            publisherId = "U_VIEW_SOLO",
                            name = "Solo",
                            startAt = LocalDateTime.of(2031, 7, 2, 10, 0),
                            isCanceled = true,
                        ),
                    )
                val inverted =
                    jpaMeetingRepository.save(
                        createMeetingSchema(
                            publisherId = "U_VIEW_INVERTED",
                            name = "Inverted",
                            startAt = LocalDateTime.of(2031, 7, 3, 10, 0),
                            endAt = LocalDateTime.of(2031, 7, 3, 9, 0),
                        ),
                    )

                then("the view carries the reason, the host, the end and only the attending participants") {
                    repository.loadSyncView(meetingId = withParticipants.id) shouldBe
                        createCalendarMeetingView(
                            meetingId = withParticipants.id,
                            meetingUid = withParticipants.meetingUid,
                            title = "Planning",
                            reason = "Quarterly planning",
                            startAt = LocalDateTime.of(2031, 7, 1, 10, 0),
                            endAt = LocalDateTime.of(2031, 7, 1, 11, 30),
                            hostId = "U_VIEW_HOST",
                            attendingUserIds = setOf("U_VIEW_A", "U_VIEW_B"),
                        )
                }

                then("a canceled meeting without end, reason or participants lasts an hour, nobody attends") {
                    repository.loadSyncView(meetingId = canceled.id) shouldBe
                        createCalendarMeetingView(
                            meetingId = canceled.id,
                            meetingUid = canceled.meetingUid,
                            title = "Solo",
                            startAt = LocalDateTime.of(2031, 7, 2, 10, 0),
                            endAt = LocalDateTime.of(2031, 7, 2, 11, 0),
                            isCanceled = true,
                            hostId = "U_VIEW_SOLO",
                        )
                }

                then("an end before the start, which Google would refuse, is replaced by start plus one hour") {
                    repository.loadSyncView(meetingId = inverted.id).shouldNotBeNull().endAt shouldBe
                        LocalDateTime.of(2031, 7, 3, 11, 0)
                }

                then("a missing meeting has no view") {
                    repository.loadSyncView(meetingId = Long.MAX_VALUE).shouldBeNull()
                }
            }
        })
