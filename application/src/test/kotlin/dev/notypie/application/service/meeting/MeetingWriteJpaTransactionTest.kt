package dev.notypie.application.service.meeting

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.createAddParticipantEvent
import dev.notypie.domain.meet.createCancelMeetingEvent
import dev.notypie.domain.meet.createRescheduleMeetingEvent
import dev.notypie.impl.command.SlackOutboundStager
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.meeting.RescheduleResult
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.schema.createMeetingSchema
import dev.notypie.schema.createMeetingSchemaWithParticipant
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class MeetingWriteJpaTransactionTest :
    BehaviorSpec({
        val store = createH2MeetingJpaStore()
        afterSpec { store.close() }

        val publisher = CommitRecordingEventPublisher()
        val stager = SlackOutboundStager(slackEventBuilder = mockk(), standupRepository = mockk())
        val clock = createFixedClock(now = LocalDateTime.of(2026, 6, 30, 12, 0))
        val host = "U_HOST_JPA"

        fun rescheduleService(meetingRepository: MeetingRepository = store.meetingRepository) =
            MeetingRescheduleService(
                meetingRepository = meetingRepository,
                reminderRepository = store.reminderRepository,
                outboundStager = stager,
                eventPublisher = publisher,
                transactionManager = store.transactionManager,
                clock = clock,
            )

        val meetingService =
            MeetingServiceImpl(
                meetingRepository = store.meetingRepository,
                retryService = mockk(),
                commandExecutor = mockk(),
                outboundStager = stager,
                eventPublisher = publisher,
                transactionManager = store.transactionManager,
            )

        fun interaction(action: () -> Unit) {
            publisher.committedMessages.clear()
            TransactionTemplate(store.transactionManager).executeWithoutResult { action() }
        }

        fun persistMeeting(startAt: LocalDateTime): MeetingSchema =
            store.inNewTransaction {
                store.jpaMeetingRepository.save(
                    createMeetingSchemaWithParticipant(
                        publisherId = host,
                        participantUserId = "U_GUEST",
                        startAt = startAt,
                    ),
                )
            }

        fun reload(meetingUid: UUID): MeetingSchema =
            store.inNewTransaction {
                checkNotNull(store.jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid))
            }

        fun armReminder(meetingId: Long) =
            store.inNewTransaction {
                store.reminderRepository.ensureReminder(
                    meetingId = meetingId,
                    offsetMinutes = 10,
                    scheduledAt = Instant.parse("2026-07-01T00:00:00Z"),
                )
            }

        fun reminderArmed(meetingId: Long): Boolean =
            store.inNewTransaction {
                store.reminderRepository.reminderExists(meetingId = meetingId, offsetMinutes = 10)
            }

        given("a reschedule submission that is resubmitted after it already committed") {
            val meeting = persistMeeting(startAt = LocalDateTime.of(2026, 7, 1, 10, 0))
            val basicInfo = createCommandBasicInfo()
            val event =
                createRescheduleMeetingEvent(
                    meetingUid = meeting.meetingUid,
                    requesterId = host,
                    newStartAt = LocalDateTime.of(2026, 7, 2, 10, 0),
                    responseBasicInfo = basicInfo,
                )
            armReminder(meetingId = meeting.id)

            `when`("the first submission commits") {
                interaction { rescheduleService().rescheduleMeeting(event = event) }

                then("the meeting moves, its reminders are dropped and participants are told once") {
                    publisher.committedEphemeralMarkdowns shouldBe listOf("Meeting rescheduled to 2026-07-02 10:00.")
                    publisher.committedMessages.filterIsInstance<OutboundMessage.ChannelMessage>().size shouldBe 1
                    reminderArmed(meetingId = meeting.id) shouldBe false
                    reload(meetingUid = meeting.meetingUid).version shouldBe 1L
                }
            }

            `when`("the host resubmits the same time") {
                armReminder(meetingId = meeting.id)
                interaction { rescheduleService().rescheduleMeeting(event = event) }

                then("only a neutral reply is sent: no notice, no reminder change, no version bump") {
                    publisher.committedEphemeralMarkdowns shouldBe
                        listOf("The meeting is already scheduled for 2026-07-02 10:00. Nothing was changed.")
                    publisher.committedMessages.filterIsInstance<OutboundMessage.ChannelMessage>().size shouldBe 0
                    reminderArmed(meetingId = meeting.id) shouldBe true
                    reload(meetingUid = meeting.meetingUid).version shouldBe 1L
                }
            }
        }

        given("a cancel submission that is resubmitted after it already committed") {
            val meeting = persistMeeting(startAt = LocalDateTime.now().plusDays(1L))
            val event = createCancelMeetingEvent(meetingUid = meeting.meetingUid, requesterId = host)

            `when`("the host submits it twice") {
                interaction { meetingService.cancelMeeting(event = event) }
                val first = publisher.committedEphemeralMarkdowns
                interaction { meetingService.cancelMeeting(event = event) }
                val second = publisher.committedEphemeralMarkdowns

                then("the second one only answers the host and leaves the version alone") {
                    first shouldBe listOf("Meeting canceled.")
                    second shouldBe listOf("Meeting was already canceled, or you are not the host.")
                    reload(meetingUid = meeting.meetingUid).version shouldBe 1L
                }
            }
        }

        given("an add-participant submission that is resubmitted after it already committed") {
            val meeting = persistMeeting(startAt = LocalDateTime.now().plusDays(1L))
            val event =
                createAddParticipantEvent(
                    meetingUid = meeting.meetingUid,
                    requesterId = host,
                    participantUserIds = listOf("U_NEW"),
                )

            `when`("the host submits it twice") {
                interaction { meetingService.addParticipants(event = event) }
                val firstApprovals = publisher.committedMessages.filterIsInstance<OutboundMessage.Approval>().size
                interaction { meetingService.addParticipants(event = event) }

                then("the second one sends no approval request and does not bump the version") {
                    firstApprovals shouldBe 1
                    publisher.committedMessages.filterIsInstance<OutboundMessage.Approval>().size shouldBe 0
                    publisher.committedEphemeralMarkdowns shouldBe listOf("Those people are already on this meeting.")
                    reload(meetingUid = meeting.meetingUid).version shouldBe 1L
                }
            }
        }

        given("a reschedule whose first attempt read the meeting before a concurrent add committed") {
            val startAt = LocalDateTime.now().plusDays(1L).truncatedTo(ChronoUnit.MINUTES)
            val meeting = persistMeeting(startAt = startAt)
            val newStartAt = startAt.plusDays(1L)
            val attemptEntityManagers = mutableListOf<Any>()
            val racingRepository =
                object : MeetingRepository by store.meetingRepository {
                    override fun rescheduleMeeting(
                        meetingUid: UUID,
                        requesterId: String,
                        newStartAt: LocalDateTime,
                    ): RescheduleResult {
                        attemptEntityManagers.add(store.currentEntityManager())
                        if (attemptEntityManagers.size == 1) {
                            store.jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid)
                            TransactionTemplate(store.transactionManager)
                                .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
                                .executeWithoutResult {
                                    store.meetingRepository.addParticipants(
                                        meetingUid = meetingUid,
                                        requesterId = host,
                                        participantUserIds = listOf("U_COMPETITOR"),
                                    )
                                }
                        }
                        return store.meetingRepository.rescheduleMeeting(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            newStartAt = newStartAt,
                        )
                    }
                }
            val event =
                createRescheduleMeetingEvent(
                    meetingUid = meeting.meetingUid,
                    requesterId = host,
                    newStartAt = newStartAt,
                )
            lateinit var outerEntityManager: Any

            `when`("the write runs under JpaTransactionManager inside the interaction transaction") {
                interaction {
                    outerEntityManager = store.currentEntityManager()
                    store.jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meeting.meetingUid)
                    rescheduleService(meetingRepository = racingRepository).rescheduleMeeting(event = event)
                }

                then("each attempt gets its own persistence context, separate from the interaction's") {
                    attemptEntityManagers.size shouldBe 2
                    attemptEntityManagers[0] shouldNotBe attemptEntityManagers[1]
                    attemptEntityManagers.forEach { it shouldNotBe outerEntityManager }
                }

                then("the retry sees the concurrent add and applies the reschedule on top of it") {
                    val reloaded = reload(meetingUid = meeting.meetingUid)
                    reloaded.startAt shouldBe newStartAt
                    reloaded.participants.map { it.userId } shouldContainExactlyInAnyOrder
                        listOf("U_GUEST", "U_COMPETITOR")
                    reloaded.version shouldBe 2L
                }

                then("participants are notified from the fresh state and the host gets one confirmation") {
                    val notice = publisher.committedMessages.filterIsInstance<OutboundMessage.ChannelMessage>().single()
                    (notice.content as MessageContent.Text).markdown shouldContain "<@U_COMPETITOR>"
                    publisher.committedEphemeralMarkdowns.size shouldBe 1
                }
            }
        }

        given("the V21 end_at repair lands between a cancel's read and its flush") {
            val startAt = LocalDateTime.now().plusDays(1L).truncatedTo(ChronoUnit.MINUTES)
            val meeting =
                store.inNewTransaction {
                    store.jpaMeetingRepository.save(
                        createMeetingSchema(publisherId = host, startAt = startAt, endAt = startAt.minusHours(1L)),
                    )
                }
            val repairSql =
                checkNotNull(javaClass.classLoader.getResource("db/migration/V21__fix_inverted_meeting_end_at.sql"))
                    .readText()
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString(separator = " ")
                    .trim()
                    .removeSuffix(";")
            var repaired = 0
            val racingRepository =
                object : MeetingRepository by store.meetingRepository {
                    override fun markMeetingCanceled(meetingUid: UUID, requesterId: String): Boolean {
                        if (repaired == 0) {
                            store.jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid)
                            repaired =
                                checkNotNull(
                                    TransactionTemplate(store.transactionManager)
                                        .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
                                        .execute {
                                            store.currentEntityManager().createNativeQuery(repairSql).executeUpdate()
                                        },
                                )
                        }
                        return store.meetingRepository.markMeetingCanceled(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                        )
                    }
                }
            val cancelingService =
                MeetingServiceImpl(
                    meetingRepository = racingRepository,
                    retryService = mockk(),
                    commandExecutor = mockk(),
                    outboundStager = stager,
                    eventPublisher = publisher,
                    transactionManager = store.transactionManager,
                )

            `when`("the cancel flushes the entity it loaded before the repair") {
                interaction {
                    cancelingService.cancelMeeting(
                        event = createCancelMeetingEvent(meetingUid = meeting.meetingUid, requesterId = host),
                    )
                }

                then("the repair's version bump forces a retry, so the inverted end_at is not written back") {
                    repaired shouldBe 1
                    val reloaded = reload(meetingUid = meeting.meetingUid)
                    reloaded.isCanceled shouldBe true
                    reloaded.endAt.shouldBeNull()
                    reloaded.version shouldBe 2L
                    publisher.committedEphemeralMarkdowns shouldBe listOf("Meeting canceled.")
                }
            }
        }
    })
