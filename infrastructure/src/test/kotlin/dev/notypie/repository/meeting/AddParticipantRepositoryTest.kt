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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class AddParticipantRepositoryTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repositoryImpl = MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository)
            val transactionTemplate = TransactionTemplate(transactionManager)

            // In production the impl is a @Transactional bean; the direct instance here has no proxy, so each
            // call is wrapped the same way to keep the optimistic lock and lazy participants inside one tx.
            fun addParticipants(meetingUid: UUID, requesterId: String, participantUserIds: List<String>) =
                transactionTemplate.execute {
                    repositoryImpl.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = participantUserIds,
                    )
                }!!

            fun persistUpcomingMeeting(host: String, existing: String): UUID {
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
                        addParticipants(
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
                        addParticipants(
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
                        addParticipants(
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
                        addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_OVERFLOW"),
                        )

                    then("it is rejected by the entity's capacity invariant") {
                        result.outcome shouldBe AddParticipantResult.Outcome.OVER_CAPACITY
                    }
                }
            }

            given("two hosts' requests race to fill the last seat") {
                `when`("both transactions read the meeting before either commits") {
                    val meeting =
                        createMeetingSchema(
                            publisherId = "U_HOST",
                            startAt = LocalDateTime.now().plusDays(1L),
                        )
                    (1..19).forEach {
                        meeting.participants.add(
                            createParticipants(meeting = meeting, userId = "U_$it"),
                        )
                    }
                    val meetingUid = jpaMeetingRepository.save(meeting).meetingUid

                    val bothRead = CountDownLatch(2)
                    val firstCommitted = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(2)

                    fun raceAdd(
                        userId: String,
                        waitFor: CountDownLatch?,
                        signalAfter: CountDownLatch?,
                    ): Future<Result<AddParticipantResult>> =
                        executor.submit(
                            Callable {
                                runCatching {
                                    transactionTemplate.execute {
                                        val outcome =
                                            repositoryImpl.addParticipants(
                                                meetingUid = meetingUid,
                                                requesterId = "U_HOST",
                                                participantUserIds = listOf(userId),
                                            )
                                        bothRead.countDown()
                                        check(
                                            bothRead.await(5L, TimeUnit.SECONDS),
                                        ) { "both workers must read before either commits" }
                                        waitFor?.let {
                                            check(
                                                it.await(5L, TimeUnit.SECONDS),
                                            ) { "first commit must land before the second" }
                                        }
                                        outcome
                                    }!!
                                }.also { signalAfter?.countDown() }
                            },
                        )

                    val first = raceAdd(userId = "U_FIRST", waitFor = null, signalAfter = firstCommitted)
                    val second = raceAdd(userId = "U_SECOND", waitFor = firstCommitted, signalAfter = null)
                    val outcomes = listOf(first.get(10L, TimeUnit.SECONDS), second.get(10L, TimeUnit.SECONDS))
                    executor.shutdownNow()

                    then("exactly one commit wins and the loser fails on the version check instead of overfilling") {
                        outcomes.count { it.isSuccess } shouldBe 1
                        outcomes
                            .first { it.isFailure }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        jpaMeetingRepository
                            .findMeetingByUidWithParticipants(meetingUid = meetingUid)!!
                            .participants.size shouldBe 20
                    }
                }
            }
        })
