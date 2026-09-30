package dev.notypie.repository.meeting

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
