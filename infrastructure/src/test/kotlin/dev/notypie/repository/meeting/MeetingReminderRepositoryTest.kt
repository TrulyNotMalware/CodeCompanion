package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createParticipants
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime

// Hibernate's own switch turns the silent in-memory paging of a collection fetch (HHH90003004) into an exception, so a
// query that loads the whole due backlog to return `limit` rows fails here instead of passing.
@DataJpaTest(properties = ["spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true"])
@ApplyExtension(extensions = [SpringExtension::class])
class MeetingReminderRepositoryTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
        private val jpaMeetingReminderRepository: JpaMeetingReminderRepository,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                MeetingReminderRepositoryImpl(
                    jpaMeetingRepository = jpaMeetingRepository,
                    jpaMeetingReminderRepository = jpaMeetingReminderRepository,
                )
            val transactionTemplate = TransactionTemplate(transactionManager)

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }!!

            // Rows commit and outlive a block; this spec is the only writer of meeting_reminder, so each block that
            // reads "everything due" starts from an empty table.
            fun clearReminders() = inTx { jpaMeetingReminderRepository.deleteAllInBatch() }

            fun persistMeeting(
                name: String,
                startAt: LocalDateTime,
                attending: List<String> = emptyList(),
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

            fun arm(meeting: MeetingSchema, offsetMinutes: Int, scheduledAt: Instant) =
                inTx {
                    repository.ensureReminder(
                        meetingId = meeting.id,
                        offsetMinutes = offsetMinutes,
                        scheduledAt = scheduledAt,
                    )
                }

            fun reminderOf(meeting: MeetingSchema, offsetMinutes: Int = 10) =
                jpaMeetingReminderRepository.findByMeetingIdAndOffsetMinutes(
                    meetingId = meeting.id,
                    offsetMinutes = offsetMinutes,
                )

            // Review M6: materialize read the old start, a reschedule deleted the rows, then the insert landed.
            given("a PENDING reminder armed from a start time a reschedule has since replaced") {
                val meeting =
                    persistMeeting(
                        name = "moved",
                        startAt = LocalDateTime.of(2031, 2, 3, 15, 0),
                        attending = listOf("U_M"),
                    )
                val staleAt = Instant.parse("2031-02-03T00:50:00Z")
                val currentAt = Instant.parse("2031-02-03T05:50:00Z")
                arm(meeting = meeting, offsetMinutes = 10, scheduledAt = staleAt)

                `when`("the next materialize pass arms the same offset for the current start") {
                    val changed = arm(meeting = meeting, offsetMinutes = 10, scheduledAt = currentAt)
                    val again = arm(meeting = meeting, offsetMinutes = 10, scheduledAt = currentAt)

                    then("the row moves to the current time instead of the unique key keeping the stale one") {
                        changed shouldBe true
                        reminderOf(meeting = meeting)!!.scheduledAt shouldBe currentAt
                        reminderOf(meeting = meeting)!!.status shouldBe MeetingReminderStatus.PENDING
                    }

                    then("arming it again for the same time changes nothing") {
                        again shouldBe false
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
                arm(meeting = meeting, offsetMinutes = 10, scheduledAt = armedAt)
                val reminderId = reminderOf(meeting = meeting)!!.id
                repository.claimReminder(reminderId = reminderId, claimToken = "token-claimed") shouldBe true

                `when`("materialize or the send-time check reaches it") {
                    val realigned =
                        arm(meeting = meeting, offsetMinutes = 10, scheduledAt = armedAt.plusSeconds(3_600L))
                    val discarded = inTx { repository.discardReminder(reminderId = reminderId) }

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
                arm(meeting = meeting, offsetMinutes = 10, scheduledAt = Instant.parse("2031-02-05T00:50:00Z"))

                `when`("it is discarded") {
                    val discarded = inTx { repository.discardReminder(reminderId = reminderOf(meeting = meeting)!!.id) }

                    then("the row is gone, so the next materialize pass can insert the offset again") {
                        discarded shouldBe true
                        repository.reminderExists(meetingId = meeting.id, offsetMinutes = 10) shouldBe false
                    }
                }
            }

            fun cancel(meeting: MeetingSchema) =
                inTx {
                    val managed = jpaMeetingRepository.findById(meeting.id).get()
                    managed.cancel()
                    jpaMeetingRepository.saveAndFlush(managed)
                }

            // Review M7: the due read filters canceled meetings, but a cancel can commit after it.
            given("a due reminder whose meeting is canceled before the claim") {
                val meeting =
                    persistMeeting(
                        name = "gone",
                        startAt = LocalDateTime.of(2031, 3, 3, 15, 0),
                        attending = listOf("U_G"),
                    )
                arm(meeting = meeting, offsetMinutes = 10, scheduledAt = Instant.parse("2031-03-03T05:50:00Z"))
                val reminderId = reminderOf(meeting = meeting)!!.id
                cancel(meeting = meeting)

                `when`("the scheduler tries to claim it") {
                    val claimed = repository.claimReminder(reminderId = reminderId, claimToken = "token-gone")

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
                arm(meeting = canceledLater, offsetMinutes = 10, scheduledAt = Instant.parse("2031-03-04T05:50:00Z"))
                arm(meeting = active, offsetMinutes = 10, scheduledAt = Instant.parse("2031-03-04T06:50:00Z"))
                val canceledId = reminderOf(meeting = canceledLater)!!.id
                val activeId = reminderOf(meeting = active)!!.id
                repository.claimReminder(reminderId = canceledId, claimToken = "token-late") shouldBe true
                repository.claimReminder(reminderId = activeId, claimToken = "token-kept") shouldBe true
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
                clearReminders()
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
                    persistMeeting(
                        name = "canceled",
                        startAt = start,
                        attending = listOf("U_X"),
                        isCanceled = true,
                    )
                val solo = persistMeeting(name = "solo", startAt = start.plusHours(3L))
                val notDue = persistMeeting(name = "later", startAt = start, attending = listOf("U_L"))
                val base = Instant.parse("2031-01-06T00:00:00Z")
                arm(meeting = third, offsetMinutes = 10, scheduledAt = base.plusSeconds(3_000L))
                arm(meeting = first, offsetMinutes = 10, scheduledAt = base.plusSeconds(1_000L))
                arm(meeting = canceled, offsetMinutes = 10, scheduledAt = base)
                arm(meeting = second, offsetMinutes = 10, scheduledAt = base.plusSeconds(2_000L))
                arm(meeting = solo, offsetMinutes = 10, scheduledAt = base.plusSeconds(4_000L))
                arm(meeting = notDue, offsetMinutes = 10, scheduledAt = base.plusSeconds(90_000L))
                val before = base.plusSeconds(10_000L)

                `when`("a batch smaller than the backlog is requested") {
                    val due = repository.findDueBefore(before = before, limit = 2)

                    then("the earliest two come back, paged in SQL rather than after loading every participant row") {
                        due.map { it.meetingTitle } shouldContainExactly listOf("first", "second")
                    }

                    then("each carries its meeting's full attending list, not a page-truncated one") {
                        due[0].attendingUserIds shouldContainExactlyInAnyOrder listOf("U_A1", "U_A2")
                        due[1].attendingUserIds shouldContainExactlyInAnyOrder listOf("U_B1", "U_B2", "U_B3")
                        due[1].startAt shouldBe start.plusHours(1L)
                    }
                }

                `when`("the whole backlog fits the batch") {
                    val due = repository.findDueBefore(before = before, limit = 10)

                    then(
                        "canceled and not-yet-due reminders stay out; a meeting with no participant rows is included",
                    ) {
                        due.map { it.meetingTitle } shouldContainExactly listOf("first", "second", "third", "solo")
                        due.last().attendingUserIds shouldBe emptyList()
                    }
                }

                `when`("nothing is due yet") {
                    then("no reminder comes back") {
                        repository.findDueBefore(before = base.minusSeconds(1L), limit = 10) shouldBe emptyList()
                    }
                }
            }
        })
