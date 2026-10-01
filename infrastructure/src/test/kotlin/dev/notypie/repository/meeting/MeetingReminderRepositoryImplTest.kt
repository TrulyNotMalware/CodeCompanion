package dev.notypie.repository.meeting

import dev.notypie.schema.createMeetingReminderSchema
import dev.notypie.schema.createMeetingSchema
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
import java.time.Duration
import java.time.Instant

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class MeetingReminderRepositoryImplTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
        private val jpaMeetingReminderRepository: JpaMeetingReminderRepository,
        private val entityManager: TestEntityManager,
    ) : BehaviorSpec({
            val repository =
                MeetingReminderRepositoryImpl(
                    jpaMeetingRepository = jpaMeetingRepository,
                    jpaMeetingReminderRepository = jpaMeetingReminderRepository,
                )

            given("due reminders for a host-only meeting and two meetings with three participants each") {
                `when`("the dispatcher asks for at most two of them") {
                    then("the oldest two come back, including the meeting without participants") {
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
