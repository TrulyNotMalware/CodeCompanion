package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.schema.createMeetingReminderSchema
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createParticipants
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@DataJpaTest
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
                )
            val transactionTemplate = TransactionTemplate(transactionManager)
            val now = Instant.parse("2031-01-01T00:00:00Z")

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }!!

            fun persistMeeting(name: String, startAt: LocalDateTime, attending: List<String>): MeetingSchema {
                val meeting = createMeetingSchema(name = name, startAt = startAt)
                attending.forEach { meeting.participants.add(createParticipants(meeting = meeting, userId = it)) }
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

            given(
                "due reminders for a meeting without participant rows and two meetings with three participants each",
            ) {
                `when`("the dispatcher asks for at most two of them") {
                    then("the oldest two come back, including the one whose meeting has no participant rows") {
                        val base = Instant.parse("2030-01-01T00:00:00Z")
                        val hostOnly = jpaMeetingRepository.save(createMeetingSchema(member = 0, startIterator = 100))
                        val first = jpaMeetingRepository.save(createMeetingSchema(member = 3, startIterator = 200))
                        val second = jpaMeetingRepository.save(createMeetingSchema(member = 3, startIterator = 300))
                        listOf(hostOnly to base, first to base.plusSeconds(60), second to base.plusSeconds(120))
                            .forEach { (meeting, scheduledAt) ->
                                jpaMeetingReminderRepository.save(
                                    createMeetingReminderSchema(meeting = meeting, scheduledAt = scheduledAt),
                                )
                            }
                        entityManager.flush()
                        entityManager.clear()

                        val due = repository.findDueBefore(before = base.plus(Duration.ofMinutes(10)), limit = 2)

                        due.map { it.meetingId } shouldContainExactly listOf(hostOnly.id, first.id)
                        due.first().attendingUserIds.shouldBeEmpty()
                        due.last().attendingUserIds shouldHaveSize 3
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
