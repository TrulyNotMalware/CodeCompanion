package dev.notypie.repository.meeting

import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createParticipants
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import java.time.LocalDateTime

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class AddParticipantRepositoryTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
    ) : BehaviorSpec({
            val repository = MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository)

            fun persistUpcomingMeeting(host: String, existing: String): java.util.UUID {
                val meeting =
                    createMeetingSchema(
                        publisherId = host,
                        startAt = LocalDateTime.now().plusDays(1L),
                    )
                meeting.participants.add(createParticipants(meeting = meeting, userId = existing))
                return jpaMeetingRepository.save(meeting).meetingUid
            }

            given("addParticipants for an upcoming meeting") {
                `when`("the host adds a new user (also passing an existing member and themselves)") {
                    val meetingUid = persistUpcomingMeeting(host = "U_HOST", existing = "U_EXISTING")

                    val result =
                        repository.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_NEW", "U_EXISTING", "U_HOST"),
                        )

                    then("only the genuinely new user is added") {
                        result.outcome shouldBe AddParticipantResult.Outcome.ADDED
                        result.addedUserIds shouldContainExactly listOf("U_NEW")
                    }

                    then("the new participant row is persisted and host/duplicate are not re-added") {
                        val userIds =
                            jpaMeetingRepository
                                .findMeetingByUidWithParticipants(meetingUid = meetingUid)!!
                                .participants
                                .map { it.userId }
                        userIds shouldContainAll listOf("U_EXISTING", "U_NEW")
                        userIds shouldNotContain "U_HOST"
                    }
                }
            }

            given("addParticipants by a non-host") {
                `when`("a non-host requester tries to add a user") {
                    val meetingUid = persistUpcomingMeeting(host = "U_HOST", existing = "U_EXISTING")

                    val result =
                        repository.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_INTRUDER",
                            participantUserIds = listOf("U_NEW"),
                        )

                    then("it is rejected as not authorized and nothing is persisted") {
                        result.outcome shouldBe AddParticipantResult.Outcome.NOT_AUTHORIZED
                        val userIds =
                            jpaMeetingRepository
                                .findMeetingByUidWithParticipants(meetingUid = meetingUid)!!
                                .participants
                                .map { it.userId }
                        userIds shouldNotContain "U_NEW"
                    }
                }
            }

            given("addParticipants for a meeting that has already started") {
                `when`("the host tries to add a user to a past-start meeting") {
                    val meeting =
                        createMeetingSchema(
                            publisherId = "U_HOST",
                            startAt = LocalDateTime.now().minusHours(1L),
                        )
                    val meetingUid = jpaMeetingRepository.save(meeting).meetingUid

                    val result =
                        repository.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_NEW"),
                        )

                    then("it is rejected because the meeting has started") {
                        result.outcome shouldBe AddParticipantResult.Outcome.MEETING_STARTED
                    }
                }
            }

            given("addParticipants that would exceed MAX_PARTICIPANTS") {
                `when`("the host adds one more to an already-full meeting") {
                    val meeting =
                        createMeetingSchema(
                            publisherId = "U_HOST",
                            startAt = LocalDateTime.now().plusDays(1L),
                        )
                    (1..20).forEach {
                        meeting.participants.add(
                            createParticipants(meeting = meeting, userId = "U_$it"),
                        )
                    }
                    val meetingUid = jpaMeetingRepository.save(meeting).meetingUid

                    val result =
                        repository.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_OVERFLOW"),
                        )

                    then("it is rejected by the entity's capacity invariant") {
                        result.outcome shouldBe AddParticipantResult.Outcome.OVER_CAPACITY
                    }
                }
            }
        })
