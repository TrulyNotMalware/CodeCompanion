package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createMeetingSchemaWithParticipant
import dev.notypie.schema.createParticipants
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class MeetingRepositoryWriteTest
    @Autowired
    constructor(
        private val jpaMeetingRepository: JpaMeetingRepository,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repositoryImpl =
                MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository, clock = Clock.systemDefaultZone())
            val transactionTemplate = TransactionTemplate(transactionManager)

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }

            fun addParticipants(meetingUid: UUID, requesterId: String, participantUserIds: List<String>) =
                inTx {
                    repositoryImpl.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = participantUserIds,
                    )
                }

            fun reschedule(meetingUid: UUID, requesterId: String, newStartAt: LocalDateTime) =
                inTx {
                    repositoryImpl.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                }

            fun cancel(meetingUid: UUID, requesterId: String): MeetingDto? =
                transactionTemplate.execute {
                    repositoryImpl.markMeetingCanceled(meetingUid = meetingUid, requesterId = requesterId)
                }

            fun reload(meetingUid: UUID) =
                jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid)!!

            fun persistUpcomingMeeting(host: String, existing: String): UUID {
                val meeting =
                    createMeetingSchema(
                        publisherId = host,
                        startAt = LocalDateTime.now().plusDays(1L),
                    )
                meeting.participants.add(createParticipants(meeting = meeting, userId = existing))
                return jpaMeetingRepository.save(meeting).meetingUid
            }

            fun <A : Any, B : Any> raceAfterBothRead(
                meetingUid: UUID,
                first: () -> A,
                second: () -> B,
            ): Pair<Result<A>, Result<B>> {
                val bothRead = CountDownLatch(2)
                val firstCommitted = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)

                fun <T : Any> worker(action: () -> T, waitFor: CountDownLatch?, signalAfter: CountDownLatch?) =
                    executor.submit(
                        Callable {
                            runCatching {
                                inTx {
                                    jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid)
                                    bothRead.countDown()
                                    check(bothRead.await(5L, TimeUnit.SECONDS)) {
                                        "both workers must read before either writes"
                                    }
                                    waitFor?.let {
                                        check(it.await(5L, TimeUnit.SECONDS)) { "first commit must land first" }
                                    }
                                    action()
                                }
                            }.also { signalAfter?.countDown() }
                        },
                    )

                val firstResult = worker(action = first, waitFor = null, signalAfter = firstCommitted)
                val secondResult = worker(action = second, waitFor = firstCommitted, signalAfter = null)
                return (firstResult.get(10L, TimeUnit.SECONDS) to secondResult.get(10L, TimeUnit.SECONDS))
                    .also { executor.shutdownNow() }
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
                        val userIds = reload(meetingUid = meetingUid).participants.map { it.userId }
                        userIds shouldContainAll listOf("U_EXISTING", "U_NEW")
                        userIds shouldNotContain "U_HOST"
                    }

                    then("the participant write bumps the meeting version") {
                        reload(meetingUid = meetingUid).version shouldBe 1L
                    }
                }
            }

            given("an add-participant submission is resubmitted") {
                `when`("the first submission committed and the host submits the same users again") {
                    val meetingUid = persistUpcomingMeeting(host = "U_HOST", existing = "U_EXISTING")

                    fun addNewUser() =
                        addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_NEW"),
                        )
                    addNewUser()

                    val resubmitted = addNewUser()

                    then("nobody is added twice and the version is not bumped again") {
                        resubmitted.outcome shouldBe AddParticipantResult.Outcome.NO_NEW_PARTICIPANTS
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.participants.map { it.userId } shouldContainExactlyInAnyOrder
                            listOf("U_EXISTING", "U_NEW")
                        reloaded.version shouldBe 1L
                    }
                }
            }

            given("two submissions add the same user at the same time") {
                `when`("both transactions read the meeting before either writes") {
                    val meetingUid = persistUpcomingMeeting(host = "U_HOST", existing = "U_EXISTING")

                    fun addSameUser() =
                        repositoryImpl.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            participantUserIds = listOf("U_SAME"),
                        )

                    val (first, second) =
                        raceAfterBothRead(
                            meetingUid = meetingUid,
                            first = { addSameUser() },
                            second = { addSameUser() },
                        )

                    then("the loser trips the participant unique key, which is classified as a retryable conflict") {
                        first.getOrThrow().outcome shouldBe AddParticipantResult.Outcome.ADDED
                        val loss = second.exceptionOrNull().shouldBeInstanceOf<DataIntegrityViolationException>()
                        loss.isMeetingWriteConflict() shouldBe true
                        reload(meetingUid = meetingUid).participants.map { it.userId } shouldContainExactlyInAnyOrder
                            listOf("U_EXISTING", "U_SAME")
                    }
                }
            }

            given("addParticipants to a meeting whose host picked nobody else") {
                `when`("the host adds the first participant") {
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchema(publisherId = "U_SOLO", startAt = LocalDateTime.now().plusDays(1L)),
                            ).meetingUid

                    val result =
                        addParticipants(
                            meetingUid = meetingUid,
                            requesterId = "U_SOLO",
                            participantUserIds = listOf("U_FIRST_GUEST"),
                        )

                    then("the meeting is found and the participant is added") {
                        result.outcome shouldBe AddParticipantResult.Outcome.ADDED
                        reload(meetingUid = meetingUid).participants.map { it.userId } shouldBe listOf("U_FIRST_GUEST")
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

                    then("it is rejected, nothing is persisted and the version is untouched") {
                        result.outcome shouldBe AddParticipantResult.Outcome.NOT_AUTHORIZED
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.participants.map { it.userId } shouldNotContain "U_NEW"
                        reloaded.version shouldBe 0L
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

            given("a meeting in 2030, judged by a repository whose clock is already past its start") {
                `when`("the host adds a participant") {
                    val startAt = LocalDateTime.of(2030, 1, 1, 10, 0)
                    val meetingUid =
                        jpaMeetingRepository
                            .save(createMeetingSchema(publisherId = "U_CLOCK_HOST", startAt = startAt))
                            .meetingUid
                    val lateClock =
                        Clock.fixed(
                            startAt.plusMinutes(1L).atZone(ZoneId.systemDefault()).toInstant(),
                            ZoneId.systemDefault(),
                        )
                    val result =
                        inTx {
                            MeetingRepositoryImpl(jpaMeetingRepository = jpaMeetingRepository, clock = lateClock)
                                .addParticipants(
                                    meetingUid = meetingUid,
                                    requesterId = "U_CLOCK_HOST",
                                    participantUserIds = listOf("U_NEW"),
                                )
                        }

                    then("the injected clock, not the wall clock, decides that the meeting has started") {
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
                `when`("both transactions read the meeting before either writes") {
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

                    val (first, second) =
                        raceAfterBothRead(
                            meetingUid = meetingUid,
                            first = {
                                repositoryImpl.addParticipants(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    participantUserIds = listOf("U_FIRST"),
                                )
                            },
                            second = {
                                repositoryImpl.addParticipants(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    participantUserIds = listOf("U_SECOND"),
                                )
                            },
                        )

                    then("exactly one commit wins and the loser fails on the version check instead of overfilling") {
                        first.getOrThrow().outcome shouldBe AddParticipantResult.Outcome.ADDED
                        second.exceptionOrNull().shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        reload(meetingUid = meetingUid).participants.size shouldBe 20
                    }
                }
            }

            given("rescheduleMeeting by the host") {
                `when`("the host moves a 90-minute meeting two hours later") {
                    val originalStart = LocalDateTime.of(2026, 8, 1, 10, 0)
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchema(
                                    publisherId = "U_HOST",
                                    startAt = originalStart,
                                    endAt = originalStart.plusMinutes(90L),
                                ),
                            ).meetingUid

                    val rescheduled =
                        reschedule(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            newStartAt = originalStart.plusHours(2L),
                        )

                    then("both times move by the same delta and the version is bumped") {
                        rescheduled.shouldBeInstanceOf<RescheduleResult.Rescheduled>()
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.startAt shouldBe originalStart.plusHours(2L)
                        reloaded.endAt shouldBe originalStart.plusHours(2L).plusMinutes(90L)
                        reloaded.version shouldBe 1L
                    }
                }

                `when`("the stored end is not after the start (a row written before end_at moved with start_at)") {
                    val originalStart = LocalDateTime.of(2026, 8, 1, 10, 0)
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchema(
                                    publisherId = "U_HOST",
                                    startAt = originalStart,
                                    endAt = originalStart.minusHours(1L),
                                ),
                            ).meetingUid

                    reschedule(meetingUid = meetingUid, requesterId = "U_HOST", newStartAt = originalStart.plusDays(1L))

                    then("the inverted duration is dropped and the end falls back to the default") {
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.startAt shouldBe originalStart.plusDays(1L)
                        reloaded.endAt.shouldBeNull()
                    }
                }
            }

            given("a reschedule is resubmitted with the time the meeting already has") {
                `when`("the first submission committed and the host submits the same time again") {
                    val originalStart = LocalDateTime.of(2026, 8, 1, 10, 0)
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchema(
                                    publisherId = "U_HOST",
                                    startAt = originalStart,
                                    endAt = originalStart.plusMinutes(30L),
                                ),
                            ).meetingUid
                    val movedStart = originalStart.plusDays(1L)

                    fun moveToSameTime() =
                        reschedule(meetingUid = meetingUid, requesterId = "U_HOST", newStartAt = movedStart)
                    moveToSameTime()

                    val resubmitted = moveToSameTime()

                    then("the second write is a no-op: same times, no version bump") {
                        resubmitted shouldBe RescheduleResult.AlreadyAtRequestedTime
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.startAt shouldBe movedStart
                        reloaded.endAt shouldBe movedStart.plusMinutes(30L)
                        reloaded.version shouldBe 1L
                    }
                }
            }

            given("rescheduleMeeting rejections") {
                val newStartAt = LocalDateTime.of(2026, 8, 1, 9, 0)

                `when`("a non-host requests the reschedule") {
                    val originalStart = LocalDateTime.of(2026, 1, 1, 10, 0)
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchemaWithParticipant(
                                    publisherId = "U_HOST",
                                    participantUserId = "U_PARTICIPANT",
                                    startAt = originalStart,
                                ),
                            ).meetingUid

                    val rescheduled =
                        reschedule(meetingUid = meetingUid, requesterId = "U_PARTICIPANT", newStartAt = newStartAt)

                    then("nothing changes, including the version another writer may be holding") {
                        rescheduled shouldBe RescheduleResult.NotAuthorized
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.startAt shouldBe originalStart
                        reloaded.version shouldBe 0L
                    }
                }

                `when`("the meeting is already canceled") {
                    val meetingUid =
                        jpaMeetingRepository
                            .save(createMeetingSchema(publisherId = "U_HOST", isCanceled = true))
                            .meetingUid

                    then("the reschedule is a no-op") {
                        reschedule(meetingUid = meetingUid, requesterId = "U_HOST", newStartAt = newStartAt) shouldBe
                            RescheduleResult.NotAuthorized
                        reload(meetingUid = meetingUid).version shouldBe 0L
                    }
                }

                `when`("no meeting matches the meetingUid") {
                    then("the reschedule reports NotAuthorized") {
                        reschedule(
                            meetingUid = UUID.randomUUID(),
                            requesterId = "U_HOST",
                            newStartAt = newStartAt,
                        ) shouldBe RescheduleResult.NotAuthorized
                    }
                }
            }

            given("two reschedules race on the same meeting") {
                `when`("both transactions read the meeting before either writes") {
                    val originalStart = LocalDateTime.of(2026, 8, 1, 10, 0)
                    val meetingUid =
                        jpaMeetingRepository
                            .save(createMeetingSchema(publisherId = "U_HOST", startAt = originalStart))
                            .meetingUid

                    val (first, second) =
                        raceAfterBothRead(
                            meetingUid = meetingUid,
                            first = {
                                repositoryImpl.rescheduleMeeting(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    newStartAt = originalStart.plusDays(1L),
                                )
                            },
                            second = {
                                repositoryImpl.rescheduleMeeting(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    newStartAt = originalStart.plusDays(2L),
                                )
                            },
                        )

                    then("the second writer conflicts instead of silently overwriting the first") {
                        first.getOrThrow().shouldBeInstanceOf<RescheduleResult.Rescheduled>()
                        second.exceptionOrNull().shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.startAt shouldBe originalStart.plusDays(1L)
                        reloaded.version shouldBe 1L
                    }
                }
            }

            given("markMeetingCanceled") {
                `when`("the host cancels their own active meeting") {
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchemaWithParticipant(
                                    publisherId = "U_HOST",
                                    participantUserId = "U_PARTICIPANT",
                                ),
                            ).meetingUid

                    val canceled = cancel(meetingUid = meetingUid, requesterId = "U_HOST")

                    then("the flag is persisted, the version is bumped and the canceled meeting is returned") {
                        canceled.shouldNotBeNull().meetingUid shouldBe meetingUid
                        canceled.isCanceled shouldBe true
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.isCanceled shouldBe true
                        reloaded.version shouldBe 1L
                    }

                    then("a resubmitted cancellation is a no-op that leaves the version alone") {
                        cancel(meetingUid = meetingUid, requesterId = "U_HOST").shouldBeNull()
                        reload(meetingUid = meetingUid).version shouldBe 1L
                    }
                }

                `when`("a non-host requests cancellation") {
                    val meetingUid =
                        jpaMeetingRepository
                            .save(
                                createMeetingSchemaWithParticipant(
                                    publisherId = "U_HOST",
                                    participantUserId = "U_PARTICIPANT",
                                ),
                            ).meetingUid

                    val canceled = cancel(meetingUid = meetingUid, requesterId = "U_PARTICIPANT")

                    then("the meeting stays active and its version is untouched") {
                        canceled.shouldBeNull()
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.isCanceled shouldBe false
                        reloaded.version shouldBe 0L
                    }
                }

                `when`("no meeting matches the meetingUid") {
                    then("the cancellation returns no meeting") {
                        cancel(meetingUid = UUID.randomUUID(), requesterId = "U_HOST").shouldBeNull()
                    }
                }
            }

            given("a cancel and an add race on the same meeting") {
                `when`("the cancel commits after both read the meeting as active") {
                    val meetingUid = persistUpcomingMeeting(host = "U_HOST", existing = "U_EXISTING")

                    val (canceled, added) =
                        raceAfterBothRead(
                            meetingUid = meetingUid,
                            first = {
                                repositoryImpl.markMeetingCanceled(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                ) != null
                            },
                            second = {
                                repositoryImpl.addParticipants(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    participantUserIds = listOf("U_LATE"),
                                )
                            },
                        )

                    then("the add loses on the version check and the canceled meeting gains nobody") {
                        canceled.getOrThrow() shouldBe true
                        added.exceptionOrNull().shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        val reloaded = reload(meetingUid = meetingUid)
                        reloaded.isCanceled shouldBe true
                        reloaded.participants.map { it.userId } shouldBe listOf("U_EXISTING")
                    }
                }
            }
        })
