package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import dev.notypie.repository.SnapshotIsolationTransactionManager
import dev.notypie.repository.createTransactionalProxy
import dev.notypie.repository.meeting.schema.MeetingReminderSchema
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.schema.createMeetingReminderSchema
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createParticipants
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime

@DataJpaTest(properties = ["spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true"])
@ApplyExtension(extensions = [SpringExtension::class])
class MeetingReminderRepositoryImplTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
        private val jpaMeetingReminderRepository: JpaMeetingReminderRepository,
        private val entityManager: TestEntityManager,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                MeetingReminderRepositoryImpl(
                    jpaMeetingRepository = jpaMeetingRepository,
                    jpaMeetingReminderRepository = jpaMeetingReminderRepository,
                    transactionManager = transactionManager,
                )
            val transactionTemplate = TransactionTemplate(transactionManager)
            val now = Instant.parse("2031-01-01T00:00:00Z")

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }!!

            fun persistMeeting(
                name: String,
                startAt: LocalDateTime,
                attending: List<String>,
                declined: List<String> = emptyList(),
                isCanceled: Boolean = false,
            ): MeetingSchema {
                val meeting = createMeetingSchema(name = name, startAt = startAt, isCanceled = isCanceled)
                attending.forEach { meeting.participants.add(createParticipants(meeting = meeting, userId = it)) }
                declined.forEach {
                    meeting.participants.add(createParticipants(meeting = meeting, userId = it, isAttending = false))
                }
                return jpaMeetingRepository.save(meeting)
            }

            fun arm(meeting: MeetingSchema, scheduledAt: Instant, startAt: LocalDateTime = meeting.startAt) =
                inTx {
                    repository.ensureReminder(
                        meetingId = meeting.id,
                        offsetMinutes = 10,
                        scheduledAt = scheduledAt,
                        startAt = startAt,
                        now = now,
                    )
                }

            fun reminderOf(meeting: MeetingSchema) =
                jpaMeetingReminderRepository.findByMeetingIdAndOffsetMinutes(meetingId = meeting.id, offsetMinutes = 10)

            given("a PENDING reminder armed from a start time a reschedule has since replaced") {
                val meeting =
                    persistMeeting(
                        name = "moved",
                        startAt = LocalDateTime.of(2031, 2, 3, 15, 0),
                        attending = listOf("U_M"),
                    )
                val staleAt = Instant.parse("2031-02-03T00:50:00Z")
                val currentAt = Instant.parse("2031-02-03T05:50:00Z")
                arm(meeting = meeting, scheduledAt = staleAt)

                `when`("the next materialize pass arms the same offset for the current start") {
                    val changed = arm(meeting = meeting, scheduledAt = currentAt)
                    val again = arm(meeting = meeting, scheduledAt = currentAt)

                    then("the row moves to the current time instead of the unique key keeping the stale one") {
                        changed shouldBe true
                        again shouldBe false
                        reminderOf(meeting = meeting)!!.scheduledAt shouldBe currentAt
                        reminderOf(meeting = meeting)!!.status shouldBe MeetingReminderStatus.PENDING
                    }
                }
            }

            given("a reminder that already left PENDING") {
                val meeting =
                    persistMeeting(
                        name = "claimed",
                        startAt = LocalDateTime.of(2031, 2, 4, 15, 0),
                        attending = listOf("U_C"),
                    )
                val armedAt = Instant.parse("2031-02-04T05:50:00Z")
                arm(meeting = meeting, scheduledAt = armedAt)
                val reminderId = reminderOf(meeting = meeting)!!.id
                repository.claimReminder(reminderId = reminderId, claimToken = "token-claimed", now = now) shouldBe true

                `when`("materialize or the send-time check reaches it") {
                    val realigned = arm(meeting = meeting, scheduledAt = armedAt.plusSeconds(3_600L))
                    val discarded = inTx { repository.discardReminder(reminderId = reminderId, scheduledAt = armedAt) }

                    then("neither touches it: the claim owns the row") {
                        realigned shouldBe false
                        discarded shouldBe false
                        reminderOf(meeting = meeting)!!.scheduledAt shouldBe armedAt
                        reminderOf(meeting = meeting)!!.status shouldBe MeetingReminderStatus.SENDING
                    }
                }
            }

            given("a PENDING reminder the send-time check found stale") {
                val meeting =
                    persistMeeting(
                        name = "stale",
                        startAt = LocalDateTime.of(2031, 2, 5, 15, 0),
                        attending = listOf("U_S"),
                    )
                val staleAt = Instant.parse("2031-02-05T00:50:00Z")
                arm(meeting = meeting, scheduledAt = staleAt)

                `when`("it is discarded at the time the check read") {
                    val discarded =
                        inTx {
                            repository.discardReminder(
                                reminderId = reminderOf(meeting = meeting)!!.id,
                                scheduledAt = staleAt,
                            )
                        }

                    then("the row is gone, so the next materialize pass can insert the offset again") {
                        discarded shouldBe true
                        repository.reminderExists(meetingId = meeting.id, offsetMinutes = 10) shouldBe false
                    }
                }
            }

            given("a stale PENDING reminder that another replica realigns after the send-time check read it") {
                val meeting =
                    persistMeeting(
                        name = "realigned",
                        startAt = LocalDateTime.of(2031, 2, 6, 15, 0),
                        attending = listOf("U_R"),
                    )
                val staleAt = Instant.parse("2031-02-06T00:50:00Z")
                val currentAt = Instant.parse("2031-02-06T05:50:00Z")
                arm(meeting = meeting, scheduledAt = staleAt)
                val reminderId = reminderOf(meeting = meeting)!!.id
                arm(meeting = meeting, scheduledAt = currentAt) shouldBe true

                `when`("the first replica discards it at the time it read") {
                    val discarded = inTx { repository.discardReminder(reminderId = reminderId, scheduledAt = staleAt) }

                    then("nothing is deleted: the realigned row is correct and stays armed") {
                        discarded shouldBe false
                        reminderOf(meeting = meeting)!!.scheduledAt shouldBe currentAt
                        reminderOf(meeting = meeting)!!.status shouldBe MeetingReminderStatus.PENDING
                    }
                }
            }

            given("a PENDING reminder already armed for the meeting's current start") {
                val meeting =
                    persistMeeting(
                        name = "rescheduled",
                        startAt = LocalDateTime.of(2031, 2, 7, 15, 0),
                        attending = listOf("U_T"),
                    )
                val currentAt = Instant.parse("2031-02-07T05:50:00Z")
                arm(meeting = meeting, scheduledAt = currentAt)

                `when`("a pass that read the old start tries to arm the old time") {
                    val moved =
                        arm(
                            meeting = meeting,
                            scheduledAt = Instant.parse("2031-02-07T00:50:00Z"),
                            startAt = LocalDateTime.of(2031, 2, 7, 10, 0),
                        )

                    then("the row is not moved back: only the meeting's current start may realign it") {
                        moved shouldBe false
                        reminderOf(meeting = meeting)!!.scheduledAt shouldBe currentAt
                    }
                }

                `when`("a realign observed a different value than the row now holds") {
                    val updated =
                        inTx {
                            jpaMeetingReminderRepository.realignPending(
                                id = reminderOf(meeting = meeting)!!.id,
                                observedAt = currentAt.minusSeconds(60L),
                                scheduledAt = currentAt.plusSeconds(60L),
                                startAt = meeting.startAt,
                                now = now,
                            )
                        }

                    then("the CAS misses and the row keeps its value") {
                        updated shouldBe 0
                        reminderOf(meeting = meeting)!!.scheduledAt shouldBe currentAt
                    }
                }
            }

            given("a PENDING reminder another replica claims between this pass's read and its realign") {
                val isolation = SnapshotIsolationTransactionManager()
                val reminders = mockk<JpaMeetingReminderRepository>()
                val isolated =
                    createTransactionalProxy<MeetingReminderRepository>(
                        target =
                            MeetingReminderRepositoryImpl(
                                jpaMeetingRepository = mockk(),
                                jpaMeetingReminderRepository = reminders,
                                transactionManager = isolation,
                            ),
                        transactionManager = isolation,
                    )
                val staleAt = Instant.parse("2031-04-01T00:50:00Z")
                val currentAt = Instant.parse("2031-04-01T05:50:00Z")
                val startAt = LocalDateTime.of(2031, 4, 1, 15, 0)
                every { reminders.findByMeetingIdAndOffsetMinutes(meetingId = 41L, offsetMinutes = 10) } answers {
                    isolation.consistentRead()
                    createMeetingReminderSchema(
                        meeting = createMeetingSchema(),
                        scheduledAt = staleAt,
                        offsetMinutes = 10,
                    )
                }
                every {
                    reminders.realignPending(
                        id = any(),
                        observedAt = staleAt,
                        scheduledAt = currentAt,
                        startAt = startAt,
                        now = now,
                    )
                } answers {
                    isolation.lockingAccessToRowChangedConcurrently(table = "meeting_reminder")
                    0
                }

                `when`("the realign runs under MariaDB snapshot isolation") {
                    val moved =
                        isolated.ensureReminder(
                            meetingId = 41L,
                            offsetMinutes = 10,
                            scheduledAt = currentAt,
                            startAt = startAt,
                            now = now,
                        )

                    then("the change counts as a CAS miss instead of failing the materialize tick") {
                        moved shouldBe false
                    }
                }
            }

            given("a meeting the host changes between this pass's read and its reminder insert") {
                val isolation = SnapshotIsolationTransactionManager()
                val reminders = mockk<JpaMeetingReminderRepository>()
                val meetings = mockk<JpaMeetingRepository>()
                val isolated =
                    createTransactionalProxy<MeetingReminderRepository>(
                        target =
                            MeetingReminderRepositoryImpl(
                                jpaMeetingRepository = meetings,
                                jpaMeetingReminderRepository = reminders,
                                transactionManager = isolation,
                            ),
                        transactionManager = isolation,
                    )
                every { reminders.findByMeetingIdAndOffsetMinutes(meetingId = 42L, offsetMinutes = 10) } answers {
                    isolation.consistentRead()
                    null
                }
                every { meetings.getReferenceById(42L) } returns createMeetingSchema()
                every { reminders.save(any<MeetingReminderSchema>()) } answers {
                    isolation.lockingAccessToRowChangedConcurrently(table = "meetings")
                    firstArg()
                }

                `when`(
                    "the insert's foreign-key check locks the changed meeting row under MariaDB snapshot isolation",
                ) {
                    val inserted =
                        isolated.ensureReminder(
                            meetingId = 42L,
                            offsetMinutes = 10,
                            scheduledAt = Instant.parse("2031-04-02T05:50:00Z"),
                            startAt = LocalDateTime.of(2031, 4, 2, 15, 0),
                            now = now,
                        )

                    then("the insert runs first in its own transaction and arms the reminder") {
                        inserted shouldBe true
                    }
                }
            }

            fun cancel(meeting: MeetingSchema) =
                inTx {
                    val managed = jpaMeetingRepository.findById(meeting.id).get()
                    managed.cancel()
                    jpaMeetingRepository.saveAndFlush(managed)
                }

            given("a due reminder whose meeting is canceled before the claim") {
                val meeting =
                    persistMeeting(
                        name = "gone",
                        startAt = LocalDateTime.of(2031, 3, 3, 15, 0),
                        attending = listOf("U_G"),
                    )
                arm(meeting = meeting, scheduledAt = Instant.parse("2031-03-03T05:50:00Z"))
                val reminderId = reminderOf(meeting = meeting)!!.id
                cancel(meeting = meeting)

                `when`("the scheduler tries to claim it") {
                    val claimed =
                        repository.claimReminder(
                            reminderId = reminderId,
                            claimToken = "token-gone",
                            now = now,
                        )

                    then("the claim fails and the row stays PENDING, so no DM is built") {
                        claimed shouldBe false
                        reminderOf(meeting = meeting)!!.status shouldBe MeetingReminderStatus.PENDING
                    }
                }
            }

            given("a reminder claimed while its meeting was active") {
                val canceledLater =
                    persistMeeting(
                        name = "late",
                        startAt = LocalDateTime.of(2031, 3, 4, 15, 0),
                        attending = listOf("U_L"),
                    )
                val active =
                    persistMeeting(
                        name = "kept",
                        startAt = LocalDateTime.of(2031, 3, 4, 16, 0),
                        attending = listOf("U_K"),
                    )
                arm(meeting = canceledLater, scheduledAt = Instant.parse("2031-03-04T05:50:00Z"))
                arm(meeting = active, scheduledAt = Instant.parse("2031-03-04T06:50:00Z"))
                val canceledId = reminderOf(meeting = canceledLater)!!.id
                val activeId = reminderOf(meeting = active)!!.id
                repository.claimReminder(reminderId = canceledId, claimToken = "token-late", now = now) shouldBe true
                repository.claimReminder(reminderId = activeId, claimToken = "token-kept", now = now) shouldBe true
                cancel(meeting = canceledLater)
                val sentAt = Instant.parse("2031-03-04T05:50:30Z")

                `when`("the outbox transaction marks them sent") {
                    val canceledSent =
                        inTx {
                            repository.markReminderSent(
                                reminderId = canceledId,
                                claimToken = "token-late",
                                sentAt = sentAt,
                            )
                        }
                    val activeSent =
                        inTx {
                            repository.markReminderSent(
                                reminderId = activeId,
                                claimToken = "token-kept",
                                sentAt = sentAt,
                            )
                        }

                    then("the canceled meeting's CAS fails, which rolls its DMs back; the active one is SENT") {
                        canceledSent shouldBe false
                        reminderOf(meeting = canceledLater)!!.status shouldBe MeetingReminderStatus.SENDING
                        activeSent shouldBe true
                        reminderOf(meeting = active)!!.status shouldBe MeetingReminderStatus.SENT
                    }
                }
            }

            given("more due reminders than one batch, spread over meetings with several participants") {
                inTx { jpaMeetingReminderRepository.deleteAllInBatch() }
                val start = LocalDateTime.of(2031, 1, 6, 10, 0)
                val first = persistMeeting(name = "first", startAt = start, attending = listOf("U_A1", "U_A2"))
                val second =
                    persistMeeting(
                        name = "second",
                        startAt = start.plusHours(1L),
                        attending = listOf("U_B1", "U_B2", "U_B3"),
                        declined = listOf("U_B4"),
                    )
                val third = persistMeeting(name = "third", startAt = start.plusHours(2L), attending = listOf("U_C1"))
                val canceled =
                    persistMeeting(name = "canceled", startAt = start, attending = listOf("U_X"), isCanceled = true)
                val solo = persistMeeting(name = "solo", startAt = start.plusHours(3L), attending = emptyList())
                val notDue = persistMeeting(name = "later", startAt = start, attending = listOf("U_L"))
                val base = Instant.parse("2031-01-06T00:00:00Z")
                arm(meeting = third, scheduledAt = base.plusSeconds(3_000L))
                arm(meeting = first, scheduledAt = base.plusSeconds(1_000L))
                arm(meeting = canceled, scheduledAt = base)
                arm(meeting = second, scheduledAt = base.plusSeconds(2_000L))
                arm(meeting = solo, scheduledAt = base.plusSeconds(4_000L))
                arm(meeting = notDue, scheduledAt = base.plusSeconds(90_000L))
                val before = base.plusSeconds(10_000L)

                `when`("a batch smaller than the backlog is requested") {
                    val due = repository.findDueBefore(before = before, limit = 2)

                    then("the earliest two come back, each with its meeting's full attending list") {
                        due.map { it.meetingTitle } shouldContainExactly listOf("first", "second")
                        due[0].attendingUserIds shouldContainExactlyInAnyOrder listOf("U_A1", "U_A2")
                        due[1].attendingUserIds shouldContainExactlyInAnyOrder listOf("U_B1", "U_B2", "U_B3")
                    }
                }

                `when`("the whole backlog fits the batch") {
                    val due = repository.findDueBefore(before = before, limit = 10)

                    then(
                        "canceled and not-yet-due reminders stay out; a meeting with no participant rows is included",
                    ) {
                        due.map { it.meetingTitle } shouldContainExactly listOf("first", "second", "third", "solo")
                        due.last().attendingUserIds.shouldBeEmpty()
                    }
                }

                `when`("nothing is due yet") {
                    then("no reminder comes back") {
                        repository.findDueBefore(before = base.minusSeconds(1L), limit = 10).shouldBeEmpty()
                    }
                }
            }

            given("a reminder claimed at an application time far from the database's own clock") {
                `when`("the stuck sweep runs with cutoffs on either side of that claim") {
                    then("only the cutoff after the claim resets it, so the sweep compares against the bound time") {
                        val claimedAt = Instant.parse("2020-01-01T00:00:00Z")
                        val meeting = jpaMeetingRepository.save(createMeetingSchema(member = 1, startIterator = 400))
                        val reminder =
                            jpaMeetingReminderRepository.save(
                                createMeetingReminderSchema(meeting = meeting, scheduledAt = claimedAt),
                            )
                        entityManager.flush()

                        repository.claimReminder(
                            reminderId = reminder.id,
                            claimToken = "token",
                            now = claimedAt,
                        ) shouldBe
                            true
                        repository.resetStuckReminders(
                            olderThan = claimedAt.minusSeconds(60),
                            now = claimedAt.plusSeconds(60),
                        ) shouldBe 0
                        repository.resetStuckReminders(
                            olderThan = claimedAt.plusSeconds(60),
                            now = claimedAt.plusSeconds(120),
                        ) shouldBe 1
                    }
                }
            }
        })
