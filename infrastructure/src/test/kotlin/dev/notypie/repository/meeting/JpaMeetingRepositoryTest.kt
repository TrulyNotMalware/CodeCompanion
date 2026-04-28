package dev.notypie.repository.meeting

import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createMeetingSchemaWithParticipant
import dev.notypie.schema.createParticipants
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import java.time.LocalDateTime
import java.util.UUID

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

            given("meetingUid persistence") {
                `when`("meeting is saved with an explicit meetingUid") {
                    val explicitUid = UUID.randomUUID()
                    val meetingSchema =
                        createMeetingSchema(
                            meetingUid = explicitUid,
                            publisherId = "U_UID_PUB",
                        )
                    meetingSchema.participants.add(
                        createParticipants(meeting = meetingSchema, userId = "U_UID_PART"),
                    )
                    val saved = repository.save(meetingSchema)

                    then("meetingUid should round-trip through persistence") {
                        saved.meetingUid shouldBe explicitUid
                        val found = repository.findMeetingWithParticipants(meetingId = saved.id)
                        found shouldNotBe null
                        found!!.meetingUid shouldBe explicitUid
                    }
                }
            }

            given("findMeetingsByUserIdAndDateRange") {
                val owner = "U_RANGE_OWNER"
                val outsider = "U_RANGE_OUTSIDER"
                val participant = "U_RANGE_PART"
                val now = LocalDateTime.now()

                // Meeting 1: starts inside [now, now+7d) — owner is publisher
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = owner,
                        participantUserId = participant,
                        name = "inside",
                        startAt = now.plusDays(1L),
                    ),
                )
                // Meeting 2: starts after the window — should be excluded
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = owner,
                        participantUserId = participant,
                        name = "outsideLater",
                        startAt = now.plusDays(10L),
                    ),
                )
                // Meeting 3: starts inside window, owner is participant only (not publisher)
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = outsider,
                        participantUserId = owner,
                        name = "participantMatch",
                        startAt = now.plusDays(2L),
                    ),
                )
                // Meeting 4: starts inside window but owner is unrelated — should be excluded
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = outsider,
                        participantUserId = "U_OTHER",
                        name = "unrelated",
                        startAt = now.plusDays(3L),
                    ),
                )

                `when`("querying [now, now+7d)") {
                    val result =
                        repository.findMeetingsByUserIdAndDateRange(
                            userId = owner,
                            startAt = now,
                            endAt = now.plusDays(7L),
                        )

                    then("should return only meetings in the window where user is publisher or participant") {
                        val names = result.map { it.name }.toSet()
                        names shouldBe setOf("inside", "participantMatch")
                    }

                    then("should be ordered by startAt ascending") {
                        val startTimes = result.map { it.startAt }
                        startTimes shouldBe startTimes.sorted()
                    }
                }

                `when`("querying a narrow window that excludes all matching meetings") {
                    val result =
                        repository.findMeetingsByUserIdAndDateRange(
                            userId = owner,
                            startAt = now.plusDays(100L),
                            endAt = now.plusDays(110L),
                        )

                    then("should return empty list") {
                        result shouldBe emptyList()
                    }
                }
            }

            given("updateParticipantAttendance") {
                `when`("a matching participant exists") {
                    val meetingKey = UUID.randomUUID()
                    val participantUserId = "U_ATTENDANCE_PART"
                    val meeting =
                        createMeetingSchema(
                            idempotencyKey = meetingKey,
                            publisherId = "U_ATTENDANCE_PUB",
                        )
                    meeting.participants.add(
                        createParticipants(meeting = meeting, userId = participantUserId),
                    )
                    repository.save(meeting)

                    val rowsUpdated =
                        repository.updateParticipantAttendance(
                            meetingIdempotencyKey = meetingKey,
                            userId = participantUserId,
                            isAttending = false,
                            absentReason = dev.notypie.domain.command.dto.interactions.RejectReason.OTHER,
                        )

                    then("should report 1 row updated") {
                        rowsUpdated shouldBe 1
                    }

                    then("persisted row should reflect the new attendance flags") {
                        val found =
                            repository
                                .findMeetingWithParticipants(meetingId = meeting.id)!!
                                .participants
                                .single { p -> p.userId == participantUserId }
                        found.isAttending shouldBe false
                        found.absentReason shouldBe
                            dev.notypie.domain.command.dto.interactions.RejectReason.OTHER
                    }
                }

                `when`("no participant matches (stale/unknown meeting or user)") {
                    val rowsUpdated =
                        repository.updateParticipantAttendance(
                            meetingIdempotencyKey = UUID.randomUUID(),
                            userId = "U_DOES_NOT_EXIST",
                            isAttending = false,
                            absentReason = dev.notypie.domain.command.dto.interactions.RejectReason.OTHER,
                        )

                    then("should report 0 rows updated and not throw") {
                        rowsUpdated shouldBe 0
                    }
                }
            }
        })
