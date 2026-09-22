package dev.notypie.repository.meeting

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.exception.meeting.DatabaseException
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createParticipants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDateTime
import java.util.UUID

class MeetingRepositoryImplTest :
    BehaviorSpec({
        val jpaMeetingRepository = mockk<JpaMeetingRepository>()
        val repository = MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository)

        given("getMeeting") {
            val meetingSchema =
                createMeetingSchema(member = 2).let { schema ->
                    createMeetingSchema(
                        id = 1L,
                        idempotencyKey = schema.idempotencyKey,
                        name = schema.name,
                        startAt = schema.startAt,
                        publisherId = schema.publisherId,
                        channel = schema.channel,
                        participants = schema.participants,
                    )
                }

            `when`("meeting exists") {
                every { jpaMeetingRepository.findMeetingWithParticipants(meetingId = 1L) } returns meetingSchema

                val result = repository.getMeeting(meetingId = 1L)

                then("should return MeetingDto with correct fields") {
                    result.meetingId shouldBe 1L
                    result.title shouldBe meetingSchema.name
                    result.creator shouldBe meetingSchema.publisherId
                    result.participants.size shouldBe 2
                }
            }

            `when`("meeting does not exist") {
                every { jpaMeetingRepository.findMeetingWithParticipants(meetingId = 999L) } returns null

                then("should throw DatabaseException") {
                    shouldThrow<DatabaseException> {
                        repository.getMeeting(meetingId = 999L)
                    }
                }
            }
        }

        given("getAllMeetingByUserId") {
            `when`("user has meetings") {
                val schema1 =
                    createMeetingSchema(
                        id = 1L,
                        publisherId = TEST_USER_ID,
                        participants =
                            mutableListOf(
                                createParticipants(
                                    meeting = createMeetingSchema(),
                                    userId = TEST_USER_ID,
                                ),
                            ),
                    )
                val schema2 =
                    createMeetingSchema(
                        id = 2L,
                        publisherId = TEST_USER_ID,
                    )
                every { jpaMeetingRepository.findAllMeetingByUserId(userId = TEST_USER_ID) } returns
                    listOf(schema1, schema2)

                val result = repository.getAllMeetingByUserId(userId = TEST_USER_ID)

                then("should return all meetings as MeetingDto list") {
                    result.size shouldBe 2
                    result[0].meetingId shouldBe 1L
                    result[1].meetingId shouldBe 2L
                }
            }

            `when`("user has no meetings") {
                every { jpaMeetingRepository.findAllMeetingByUserId(userId = "U_NONE") } returns emptyList()

                val result = repository.getAllMeetingByUserId(userId = "U_NONE")

                then("should return empty list") {
                    result shouldBe emptyList()
                }
            }
        }

        given("getParticipants") {
            `when`("meeting has participants") {
                val schema =
                    createMeetingSchema(
                        id = 1L,
                        participants =
                            mutableListOf(
                                createParticipants(meeting = createMeetingSchema(), userId = "U001"),
                                createParticipants(meeting = createMeetingSchema(), userId = "U002"),
                            ),
                    )
                every { jpaMeetingRepository.findMeetingWithParticipants(meetingId = 1L) } returns schema

                val result = repository.getParticipants(meetingId = 1L)

                then("should return participant user ids") {
                    result shouldBe listOf("U001", "U002")
                }
            }

            `when`("meeting does not exist") {
                every { jpaMeetingRepository.findMeetingWithParticipants(meetingId = 999L) } returns null

                then("should throw DatabaseException") {
                    shouldThrow<DatabaseException> {
                        repository.getParticipants(meetingId = 999L)
                    }
                }
            }
        }

        given("participantExists") {
            val meetingKey = UUID.randomUUID()

            `when`("the participant row is present") {
                every {
                    jpaMeetingRepository.existsParticipant(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_PRESENT",
                    )
                } returns true

                then("returns true so callers can treat a zero-row UPDATE as a no-op instead of failing") {
                    repository.participantExists(meetingKey, "U_PRESENT") shouldBe true
                }
            }

            `when`("the participant row is missing") {
                every {
                    jpaMeetingRepository.existsParticipant(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_MISSING",
                    )
                } returns false

                then("returns false so callers can distinguish a truly-missing row from a no-op UPDATE") {
                    repository.participantExists(meetingKey, "U_MISSING") shouldBe false
                }
            }
        }

        given("rescheduleMeeting") {
            val meetingUid = UUID.randomUUID()
            val originalStart = LocalDateTime.of(2026, 8, 1, 10, 0)

            `when`("the meeting lasts 90 minutes and is moved two hours later") {
                every { jpaMeetingRepository.findMeetingByUidForUpdate(meetingUid = meetingUid) } returns
                    createMeetingSchema(
                        meetingUid = meetingUid,
                        startAt = originalStart,
                        endAt = originalStart.plusMinutes(90L),
                    )
                val newEndAt = slot<LocalDateTime?>()
                every {
                    jpaMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = TEST_USER_ID,
                        newStartAt = originalStart.plusHours(2L),
                        newEndAt = captureNullable(newEndAt),
                    )
                } returns 1

                val result =
                    repository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = TEST_USER_ID,
                        newStartAt = originalStart.plusHours(2L),
                    )

                then("the duration is preserved so endAt stays after startAt") {
                    result shouldBe true
                    newEndAt.captured shouldBe originalStart.plusHours(2L).plusMinutes(90L)
                }
            }

            `when`("the meeting has no explicit end") {
                every { jpaMeetingRepository.findMeetingByUidForUpdate(meetingUid = meetingUid) } returns
                    createMeetingSchema(meetingUid = meetingUid, startAt = originalStart, endAt = null)
                every {
                    jpaMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = TEST_USER_ID,
                        newStartAt = originalStart.plusHours(2L),
                        newEndAt = null,
                    )
                } returns 1

                then("endAt stays null and the row still updates") {
                    repository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = TEST_USER_ID,
                        newStartAt = originalStart.plusHours(2L),
                    ) shouldBe true
                }
            }

            `when`("the meeting does not exist") {
                every { jpaMeetingRepository.findMeetingByUidForUpdate(meetingUid = meetingUid) } returns null

                then("nothing is updated and the caller sees false") {
                    repository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = TEST_USER_ID,
                        newStartAt = originalStart.plusHours(2L),
                    ) shouldBe false
                }
            }
        }
    })
