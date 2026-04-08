package dev.notypie.repository.meeting

import dev.notypie.schema.createMeetingSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaMeetingRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaMeetingRepository,
    ) : BehaviorSpec({

            given("save and findById") {
                `when`("saving a meeting with participants") {
                    val meetingSchema = createMeetingSchema(member = 3)
                    val saved = repository.save(meetingSchema)

                    then("should persist with generated id") {
                        saved.id shouldNotBe 0L
                        saved.publisherId shouldBe meetingSchema.publisherId
                    }

                    then("should be retrievable by id") {
                        val found = repository.findById(saved.id)
                        found.isPresent shouldBe true
                        found.get().publisherId shouldBe meetingSchema.publisherId
                    }

                    then("should persist all participants") {
                        val found = repository.findMeetingWithParticipants(meetingId = saved.id)
                        found!!.participants.size shouldBe 3
                    }
                }
            }

            given("findMeetingWithParticipants") {
                `when`("meeting exists with participants") {
                    val meetingSchema = createMeetingSchema(member = 2, startIterator = 10)
                    val saved = repository.save(meetingSchema)

                    val result = repository.findMeetingWithParticipants(meetingId = saved.id)

                    then("should return meeting with fetched participants") {
                        result shouldNotBe null
                        result!!.id shouldBe saved.id
                        result.participants.size shouldBe 2
                    }

                    then("participant user ids should match saved data") {
                        val userIds = result!!.participants.map { it.userId }
                        val expectedUserIds = saved.participants.map { it.userId }
                        userIds.shouldContainAll(expectedUserIds)
                    }
                }

                `when`("meeting does not exist") {
                    val result = repository.findMeetingWithParticipants(meetingId = -1L)

                    then("should return null") {
                        result shouldBe null
                    }
                }
            }

            given("findAllMeetingByUserId") {
                `when`("user is the publisher") {
                    val meeting = createMeetingSchema(member = 1, startIterator = 100)
                    repository.save(meeting)

                    val result = repository.findAllMeetingByUserId(userId = meeting.publisherId)

                    then("should return the meeting") {
                        result.size shouldBe 1
                        result.first().publisherId shouldBe meeting.publisherId
                    }
                }

                `when`("user is a participant but not publisher") {
                    val meeting = createMeetingSchema(member = 2, startIterator = 200)
                    repository.save(meeting)
                    val participantId = meeting.participants.last().userId

                    val result = repository.findAllMeetingByUserId(userId = participantId)

                    then("should return the meeting where user is participant") {
                        result.size shouldBe 1
                        result.first().participants.any { it.userId == participantId } shouldBe true
                    }
                }

                `when`("user has no meetings") {
                    val result = repository.findAllMeetingByUserId(userId = "U_NONEXISTENT_USER")

                    then("should return empty list") {
                        result shouldBe emptyList()
                    }
                }
            }
        })
