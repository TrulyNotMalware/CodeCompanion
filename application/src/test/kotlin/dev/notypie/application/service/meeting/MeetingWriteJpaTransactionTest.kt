package dev.notypie.application.service.meeting

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.interaction.SlackInteractionHandlerImpl
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.createAddParticipantEvent
import dev.notypie.domain.meet.createCancelMeetingEvent
import dev.notypie.domain.meet.createRescheduleMeetingEvent
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.SlackOutboundStager
import dev.notypie.impl.command.slack.createInteractionPayloadInput
import dev.notypie.impl.command.slack.selectedApplyButtonStates
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
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
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
                commandExecutor = mockk(),
                outboundStager = stager,
                eventPublisher = publisher,
                transactionManager = store.transactionManager,
            )

        // The inline path: no MeetingWriteDeferral scope is open, so the write runs at once inside this outer
        // transaction and its REQUIRES_NEW suspends it (a second EntityManager and connection). Production
        // interactions defer instead; `throughHandler` below drives that path.
        fun interaction(action: () -> Unit) {
            publisher.committedMessages.clear()
            TransactionTemplate(store.transactionManager).executeWithoutResult { action() }
        }

        fun throughHandler(onCommand: () -> Unit) {
            publisher.committedMessages.clear()
            val parser = mockk<InteractionPayloadParser>()
            every { parser.parseStringPayload(payload = any()) } returns
                createInteractionPayloadInput(
                    commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                    currentAction = selectedApplyButtonStates(),
                    states = listOf(selectedApplyButtonStates()),
                    idempotencyKey = UUID.randomUUID(),
                )
            val executor = mockk<CommandExecutor>()
            every { executor.execute(command = any<Command<*>>()) } answers {
                onCommand()
                CommandOutput.empty()
            }
            val roleResolver = mockk<CommandRoleResolver>()
            every { roleResolver.resolve(userId = any()) } returns UserRole.USER
            SlackInteractionHandlerImpl(
                interactionPayloadParser = parser,
                applicationEventPublisher = mockk(relaxed = true),
                commandExecutor = executor,
                submissionParseObserver = SubmissionParseObserver.NONE,
                commandRoleResolver = roleResolver,
                transactionManager = store.transactionManager,
            ).handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")
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
                    startAt = LocalDateTime.of(2026, 7, 1, 10, 0),
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

            `when`("the write runs inline under JpaTransactionManager inside an outer transaction") {
                interaction {
                    outerEntityManager = store.currentEntityManager()
                    store.jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meeting.meetingUid)
                    rescheduleService(meetingRepository = racingRepository).rescheduleMeeting(event = event)
                }

                then("each attempt gets its own persistence context: REQUIRES_NEW suspends the outer one") {
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

        // Review T25. The host asks to move the meeting back to its original time T0; between the interaction's read
        // and the deferred write another request has moved it to T1. Only a fresh persistence context sees T1.
        given("a deferred reschedule behind an interaction that read the meeting before a concurrent move committed") {
            val originalStart = LocalDateTime.of(2026, 7, 10, 10, 0)
            val movedStart = LocalDateTime.of(2026, 7, 10, 15, 0)

            data class DeferredRun(
                val interactionEntityManager: Any,
                val attemptEntityManagers: List<Any>,
            )

            fun rescheduleBackThroughHandler(meetingUid: UUID): DeferredRun {
                lateinit var interactionEntityManager: Any
                val attemptEntityManagers = mutableListOf<Any>()
                val recordingRepository =
                    object : MeetingRepository by store.meetingRepository {
                        override fun rescheduleMeeting(
                            meetingUid: UUID,
                            requesterId: String,
                            newStartAt: LocalDateTime,
                        ): RescheduleResult {
                            attemptEntityManagers.add(store.currentEntityManager())
                            return store.meetingRepository.rescheduleMeeting(
                                meetingUid = meetingUid,
                                requesterId = requesterId,
                                newStartAt = newStartAt,
                            )
                        }
                    }
                throughHandler {
                    interactionEntityManager = store.currentEntityManager()
                    store.jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid)
                    TransactionTemplate(store.transactionManager)
                        .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
                        .executeWithoutResult {
                            store.meetingRepository.rescheduleMeeting(
                                meetingUid = meetingUid,
                                requesterId = host,
                                newStartAt = movedStart,
                            )
                        }
                    rescheduleService(meetingRepository = recordingRepository).rescheduleMeeting(
                        event =
                            createRescheduleMeetingEvent(
                                meetingUid = meetingUid,
                                requesterId = host,
                                newStartAt = originalStart,
                            ),
                    )
                }
                return DeferredRun(
                    interactionEntityManager = interactionEntityManager,
                    attemptEntityManagers = attemptEntityManagers,
                )
            }

            `when`("no EntityManager outlives a transaction (spring.jpa.open-in-view: false, every profile)") {
                val meeting = persistMeeting(startAt = originalStart)
                val run = rescheduleBackThroughHandler(meetingUid = meeting.meetingUid)

                then("the write runs after the interaction, in a persistence context of its own") {
                    run.attemptEntityManagers.size shouldBe 1
                    run.attemptEntityManagers.single() shouldNotBe run.interactionEntityManager
                }

                then("it reads the concurrent move and puts the meeting back, answering the host truthfully") {
                    val reloaded = reload(meetingUid = meeting.meetingUid)
                    reloaded.startAt shouldBe originalStart
                    reloaded.version shouldBe 2L
                    publisher.committedEphemeralMarkdowns shouldBe listOf("Meeting rescheduled to 2026-07-10 10:00.")
                }
            }

            `when`("an open-in-view EntityManager is bound to the request, as Boot's default would do") {
                val meeting = persistMeeting(startAt = originalStart)
                val run =
                    store.withOpenEntityManagerInView { rescheduleBackThroughHandler(meetingUid = meeting.meetingUid) }

                then("the write's first attempt reuses the interaction's EntityManager") {
                    run.attemptEntityManagers.single() shouldBe run.interactionEntityManager
                }

                then(
                    "it answers from the entity the interaction loaded: the stale reply open-in-view: false rules out",
                ) {
                    reload(meetingUid = meeting.meetingUid).startAt shouldBe movedStart
                    publisher.committedEphemeralMarkdowns shouldBe
                        listOf("The meeting is already scheduled for 2026-07-10 10:00. Nothing was changed.")
                }
            }
        }

        given("the shipped profiles") {
            listOf("local", "dev", "prod", "slack-live").forEach { profile ->
                `when`("the $profile profile is active") {
                    then("spring.jpa.open-in-view resolves to false") {
                        ApplicationContextRunner()
                            .withInitializer(ConfigDataApplicationContextInitializer())
                            .withPropertyValues("spring.profiles.active=$profile")
                            .run { context ->
                                context.environment.getProperty("spring.jpa.open-in-view", Boolean::class.java) shouldBe
                                    false
                            }
                    }
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
