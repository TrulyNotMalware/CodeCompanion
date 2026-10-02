package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.RejectReason
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
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.UUID

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class JpaMeetingRepositoryTest
    @Autowired
    constructor(
        private val repository: JpaMeetingRepository,
        private val jdbcTemplate: JdbcTemplate,
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

                `when`("user is one of five participants") {
                    val meeting = createMeetingSchema(member = 5, startIterator = 300)
                    repository.save(meeting)
                    val participantId = meeting.participants.last().userId

                    val result = repository.findAllMeetingByUserId(userId = participantId)

                    then("the fetched participant collection is complete, not filtered down to the caller") {
                        result.size shouldBe 1
                        result.first().participants.size shouldBe 5
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

                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = owner,
                        participantUserId = participant,
                        name = "inside",
                        startAt = now.plusDays(1L),
                    ),
                )
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = owner,
                        participantUserId = participant,
                        name = "outsideLater",
                        startAt = now.plusDays(10L),
                    ),
                )
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = outsider,
                        participantUserId = owner,
                        name = "participantMatch",
                        startAt = now.plusDays(2L),
                    ),
                )
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

                `when`("querying as a participant of a crowded meeting") {
                    val meeting =
                        createMeetingSchema(
                            publisherId = outsider,
                            name = "crowded",
                            startAt = now.plusDays(4L),
                        )
                    listOf("U_RANGE_P1", "U_RANGE_P2", "U_RANGE_P3", owner).forEach { userId ->
                        meeting.participants.add(createParticipants(meeting = meeting, userId = userId))
                    }
                    repository.save(meeting)

                    val result =
                        repository.findMeetingsByUserIdAndDateRange(
                            userId = owner,
                            startAt = now.plusDays(4L).minusHours(1L),
                            endAt = now.plusDays(4L).plusHours(1L),
                        )

                    then("the crowded meeting carries all four participants, not only the caller") {
                        result.map { it.name } shouldBe listOf("crowded")
                        result.first().participants.size shouldBe 4
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
                            absentReason = dev.notypie.domain.meet.entity.RejectReason.OTHER,
                            absentReasonDetail = "Out of town for a family event",
                        )

                    then("should report 1 row updated") {
                        rowsUpdated shouldBe 1
                    }

                    then("persisted row should reflect the new attendance flags and detail") {
                        val found =
                            repository
                                .findMeetingWithParticipants(meetingId = meeting.id)!!
                                .participants
                                .single { p -> p.userId == participantUserId }
                        found.isAttending shouldBe false
                        found.absentReason shouldBe
                            dev.notypie.domain.meet.entity.RejectReason.OTHER
                        found.absentReasonDetail shouldBe "Out of town for a family event"
                    }
                }

                `when`("the detail is exactly RejectReason.MAX_DETAIL_LENGTH characters") {
                    val meetingKey = UUID.randomUUID()
                    val participantUserId = "U_ATTENDANCE_LIMIT"
                    val meeting = createMeetingSchema(idempotencyKey = meetingKey, publisherId = "U_LIMIT_PUB")
                    meeting.participants.add(createParticipants(meeting = meeting, userId = participantUserId))
                    repository.save(meeting)

                    fun decline(detail: String) =
                        repository.updateParticipantAttendance(
                            meetingIdempotencyKey = meetingKey,
                            userId = participantUserId,
                            isAttending = false,
                            absentReason = RejectReason.OTHER,
                            absentReasonDetail = detail,
                        )

                    fun storedDetail() =
                        repository
                            .findMeetingWithParticipants(meetingId = meeting.id)!!
                            .participants
                            .single()
                            .absentReasonDetail

                    then(
                        "the mapped column is exactly the domain limit wide and a note of that length is stored intact",
                    ) {
                        jdbcTemplate.queryForObject(
                            "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE TABLE_NAME = 'MEETING_PARTICIPANTS' AND COLUMN_NAME = 'ABSENT_REASON_DETAIL'",
                            Int::class.java,
                        ) shouldBe RejectReason.MAX_DETAIL_LENGTH
                        decline(detail = "a".repeat(RejectReason.MAX_DETAIL_LENGTH)) shouldBe 1
                        storedDetail() shouldBe "a".repeat(RejectReason.MAX_DETAIL_LENGTH)
                    }

                    then("H2 counts UTF-16 units: a note at the limit in emoji is cut to the column width") {
                        val emojiAtLimit = "😀".repeat(RejectReason.MAX_DETAIL_LENGTH)
                        decline(detail = emojiAtLimit) shouldBe 1
                        storedDetail()!!.length shouldBe RejectReason.MAX_DETAIL_LENGTH
                        storedDetail() shouldNotBe emojiAtLimit
                    }
                }

                `when`("no participant matches (stale/unknown meeting or user)") {
                    val rowsUpdated =
                        repository.updateParticipantAttendance(
                            meetingIdempotencyKey = UUID.randomUUID(),
                            userId = "U_DOES_NOT_EXIST",
                            isAttending = false,
                            absentReason = dev.notypie.domain.meet.entity.RejectReason.OTHER,
                            absentReasonDetail = null,
                        )

                    then("should report 0 rows updated and not throw") {
                        rowsUpdated shouldBe 0
                    }
                }
            }

            given("a meeting whose host picked nobody else") {
                val soloStart = LocalDateTime.of(2030, 3, 4, 10, 0)
                val solo =
                    repository.save(
                        createMeetingSchema(publisherId = "U_SOLO_HOST", name = "solo", startAt = soloStart),
                    )

                `when`("it is read by id") {
                    val result = repository.findMeetingWithParticipants(meetingId = solo.id)

                    then("it is found with an empty participant list") {
                        result!!.name shouldBe "solo"
                        result.participants shouldBe emptyList()
                    }
                }

                `when`("it is read by uid") {
                    val result = repository.findMeetingByUidWithParticipants(meetingUid = solo.meetingUid)

                    then("it is found with an empty participant list") {
                        result!!.participants shouldBe emptyList()
                    }
                }

                `when`("the host lists all their meetings") {
                    val result = repository.findAllMeetingByUserId(userId = "U_SOLO_HOST")

                    then("the meeting is listed") {
                        result.map { it.name } shouldBe listOf("solo")
                    }
                }

                `when`("the host lists meetings in a range that covers it") {
                    val result =
                        repository.findMeetingsByUserIdAndDateRange(
                            userId = "U_SOLO_HOST",
                            startAt = soloStart.minusHours(1L),
                            endAt = soloStart.plusHours(1L),
                        )

                    then("the meeting is listed") {
                        result.map { it.name } shouldBe listOf("solo")
                    }
                }

                `when`("the scheduler sweeps active meetings in a window that covers it") {
                    val result =
                        repository.findActiveByStartAtBetween(
                            startAt = soloStart.minusMinutes(1L),
                            endAt = soloStart.plusMinutes(1L),
                        )

                    then("the meeting is swept with no participants") {
                        result.map { it.name } shouldBe listOf("solo")
                        result.single().participants shouldBe emptyList()
                    }
                }
            }

            given("findActiveByStartAtBetween over active and canceled meetings") {
                val windowStart = LocalDateTime.of(2030, 5, 6, 0, 0)
                repository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = "U_SWEEP_HOST",
                        participantUserId = "U_SWEEP_A",
                        name = "active",
                        startAt = windowStart.plusHours(9L),
                    ),
                )
                repository.save(
                    createMeetingSchema(
                        publisherId = "U_SWEEP_HOST",
                        name = "canceled",
                        startAt = windowStart.plusHours(10L),
                        isCanceled = true,
                    ),
                )
                val crowded =
                    createMeetingSchema(
                        publisherId = "U_SWEEP_HOST",
                        name = "crowded",
                        startAt = windowStart.plusHours(11L),
                    )
                listOf("U_SWEEP_C", "U_SWEEP_D", "U_SWEEP_E").forEach { userId ->
                    crowded.participants.add(createParticipants(meeting = crowded, userId = userId))
                }
                repository.save(crowded)

                `when`("the window covers all of them") {
                    val result =
                        repository.findActiveByStartAtBetween(
                            startAt = windowStart,
                            endAt = windowStart.plusDays(1L),
                        )

                    then("canceled meetings are excluded and each active meeting appears once, ordered by start") {
                        result.map { it.name } shouldBe listOf("active", "crowded")
                        result.last().participants.size shouldBe 3
                    }
                }
            }
        })
