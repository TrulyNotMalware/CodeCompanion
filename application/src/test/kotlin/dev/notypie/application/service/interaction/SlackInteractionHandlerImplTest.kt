package dev.notypie.application.service.interaction

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.meeting.CommitRecordingEventPublisher
import dev.notypie.application.service.meeting.MeetingServiceImpl
import dev.notypie.application.service.meeting.MeetingWriteDeferral
import dev.notypie.application.service.meeting.createBoundedH2DataSource
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.domain.meet.createAddParticipantEvent
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.SlackOutboundStager
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.States
import dev.notypie.impl.command.slack.createInteractionPayloadInput
import dev.notypie.impl.command.slack.selectedApplyButtonStates
import dev.notypie.impl.command.slack.selectedRejectButtonStates
import dev.notypie.repository.meeting.AddParticipantResult
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.templates.DeclineReasonModalIds
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SlackInteractionHandlerImplTest :
    BehaviorSpec({
        val payloadParser = mockk<InteractionPayloadParser>()
        val applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val commandExecutor = mockk<CommandExecutor>(relaxed = true)
        val commandRoleResolver = mockk<CommandRoleResolver>()
        every { commandRoleResolver.resolve(userId = any()) } returns UserRole.USER
        val handler =
            SlackInteractionHandlerImpl(
                interactionPayloadParser = payloadParser,
                applicationEventPublisher = applicationEventPublisher,
                commandExecutor = commandExecutor,
                submissionParseObserver = SubmissionParseObserver.NONE,
                commandRoleResolver = commandRoleResolver,
                transactionManager = createH2TransactionManager(),
            )

        val poolSize = 3
        val pool = createBoundedH2DataSource(maxConnections = poolSize)
        afterSpec { pool.close() }

        fun primaryPayload() =
            createInteractionPayloadInput(
                commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                currentAction = selectedApplyButtonStates(),
                states = listOf(selectedApplyButtonStates()),
                idempotencyKey = UUID.randomUUID(),
            )

        fun isolatedHandler(
            transactionManager: PlatformTransactionManager,
            executor: CommandExecutor,
            roleResolver: CommandRoleResolver,
        ): SlackInteractionHandlerImpl {
            val parser = mockk<InteractionPayloadParser>()
            every { parser.parseStringPayload(payload = any()) } answers { primaryPayload() }
            return SlackInteractionHandlerImpl(
                interactionPayloadParser = parser,
                applicationEventPublisher = mockk(relaxed = true),
                commandExecutor = executor,
                submissionParseObserver = SubmissionParseObserver.NONE,
                commandRoleResolver = roleResolver,
                transactionManager = transactionManager,
            )
        }

        given("a command that queues a meeting write") {
            val events = CopyOnWriteArrayList<String>()
            val executor = mockk<CommandExecutor>()
            val deferringHandler =
                isolatedHandler(
                    transactionManager = createH2TransactionManager(),
                    executor = executor,
                    roleResolver = commandRoleResolver,
                )

            fun stubExecution(failAfterQueueing: Boolean) {
                events.clear()
                every { executor.execute(command = any<Command<*>>()) } answers {
                    val inTransaction = TransactionSynchronizationManager.isActualTransactionActive()
                    events.add("command in transaction=$inTransaction")
                    TransactionSynchronizationManager.registerSynchronization(
                        object : TransactionSynchronization {
                            override fun afterCompletion(status: Int) {
                                events.add("interaction transaction completed")
                            }
                        },
                    )
                    MeetingWriteDeferral.runOrDefer {
                        val writeInTransaction = TransactionSynchronizationManager.isActualTransactionActive()
                        events.add("meeting write in transaction=$writeInTransaction")
                    }
                    if (failAfterQueueing) throw IllegalStateException("later intent failed")
                    CommandOutput.empty()
                }
            }

            `when`("the interaction transaction commits") {
                stubExecution(failAfterQueueing = false)
                deferringHandler.handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")

                then("the command runs inside it and the meeting write runs only after it has completed") {
                    events shouldBe
                        listOf(
                            "command in transaction=true",
                            "interaction transaction completed",
                            "meeting write in transaction=false",
                        )
                }
            }

            `when`("the interaction transaction fails after the write was queued") {
                stubExecution(failAfterQueueing = true)
                val escaped =
                    runCatching {
                        deferringHandler.handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")
                    }.exceptionOrNull()

                then("the request fails and the meeting write never runs") {
                    (escaped is IllegalStateException) shouldBe true
                    events shouldBe listOf("command in transaction=true", "interaction transaction completed")
                }
            }
        }

        given("as many concurrent meeting interactions as the pool has connections") {
            val transactionManager = createH2TransactionManager(dataSource = pool)
            val publisher = CommitRecordingEventPublisher()
            val meetingRepository = mockk<MeetingRepository>()
            every {
                meetingRepository.addParticipants(meetingUid = any(), requesterId = any(), participantUserIds = any())
            } returns
                AddParticipantResult(
                    outcome = AddParticipantResult.Outcome.ADDED,
                    addedUserIds = listOf("U_A"),
                    meeting = createMeetingDto(),
                )
            val meetingService =
                MeetingServiceImpl(
                    meetingRepository = meetingRepository,
                    retryService = mockk(),
                    commandExecutor = mockk(),
                    outboundStager = SlackOutboundStager(slackEventBuilder = mockk(), standupRepository = mockk()),
                    eventPublisher = publisher,
                    transactionManager = transactionManager,
                )
            val event = createAddParticipantEvent(participantUserIds = listOf("U_A"))

            fun runConcurrently(interaction: (CyclicBarrier) -> Unit) {
                publisher.committedMessages.clear()
                val allHoldAConnection = CyclicBarrier(poolSize)
                val executor = Executors.newFixedThreadPool(poolSize)
                try {
                    (1..poolSize)
                        .map { executor.submit { interaction(allHoldAConnection) } }
                        .forEach { it.get(20L, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }
            }

            `when`("each write runs inside its interaction transaction, as before this change") {
                runConcurrently { allHoldAConnection ->
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        allHoldAConnection.await(5L, TimeUnit.SECONDS)
                        meetingService.addParticipants(event = event)
                    }
                }

                then("every isolated write times out waiting for a second connection") {
                    publisher.committedEphemeralMarkdowns shouldBe
                        List(size = poolSize) { "Failed to add participants. Please try again later." }
                }
            }

            `when`("the interactions go through handleInteraction") {
                val executor = mockk<CommandExecutor>()
                every { executor.execute(command = any<Command<*>>()) } answers {
                    meetingService.addParticipants(event = event)
                    CommandOutput.empty()
                }
                val roleResolver = mockk<CommandRoleResolver>()
                val allHoldAConnection = CyclicBarrier(poolSize)
                every { roleResolver.resolve(userId = any()) } answers {
                    allHoldAConnection.await(5L, TimeUnit.SECONDS)
                    UserRole.USER
                }
                val poolHandler =
                    isolatedHandler(
                        transactionManager = transactionManager,
                        executor = executor,
                        roleResolver = roleResolver,
                    )

                runConcurrently {
                    poolHandler.handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")
                }

                then("every write succeeds because each thread holds at most one connection at a time") {
                    publisher.committedEphemeralMarkdowns shouldBe
                        List(size = poolSize) { "Added <@U_A> to the meeting." }
                }
            }
        }

        given("legacy whitelist constant") {
            `when`("LEGACY_AUTO_REJECT_TYPES is inspected") {
                then("contains exactly APPLY_REQUEST and APPROVAL_REQUEST") {
                    SlackInteractionHandlerImpl.LEGACY_AUTO_REJECT_TYPES shouldBe
                        setOf(
                            CommandDetailType.APPLY_REQUEST,
                            CommandDetailType.APPROVAL_REQUEST,
                        )
                }

                then("does NOT contain new CommandDetailType values that need context routing") {
                    SlackInteractionHandlerImpl.LEGACY_AUTO_REJECT_TYPES shouldNotContainAnyOf
                        setOf(
                            CommandDetailType.MEETING_APPROVAL_REQUEST,
                            CommandDetailType.MEETING_CREATE_REQUEST,
                            CommandDetailType.APPROVAL_CALLBACK,
                            CommandDetailType.NOTHING,
                        )
                }
            }
        }

        given("constructor") {
            `when`("handler is instantiated with required dependencies") {
                then("resolves without error") {
                    (handler != null) shouldBe true
                }
            }
        }

        given("handleInteraction for a primary APPLY action on a context-routed type") {
            `when`("handler processes the payload") {
                then("commandExecutor executes an InteractionCommand (not the legacy reject path)") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                            currentAction = selectedApplyButtonStates(),
                            states = listOf(selectedApplyButtonStates()),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    handler.handleInteraction(
                        headers = LinkedMultiValueMap(),
                        payload = "dummy-payload",
                    )

                    verify(exactly = 1) {
                        commandExecutor.execute(command = match<Command<*>> { it is InteractionCommand })
                    }
                    verify(exactly = 0) {
                        commandExecutor.execute(command = match<Command<*>> { it is ReplaceTextResponseCommand })
                    }
                }

                then("the actor's role is resolved from the repository instead of being assumed USER") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher, commandRoleResolver)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                            currentAction = selectedApplyButtonStates(),
                            states = listOf(selectedApplyButtonStates()),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload
                    every { commandRoleResolver.resolve(userId = any()) } returns UserRole.ADMIN

                    handler.handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")

                    verify(exactly = 1) { commandRoleResolver.resolve(userId = payload.user.id) }
                }
            }
        }

        given("handleInteraction for REJECT on a legacy type (APPROVAL_REQUEST)") {
            `when`("handler processes the payload") {
                then("commandExecutor executes the legacy ReplaceTextResponseCommand") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.APPROVAL_REQUEST,
                            currentAction = selectedRejectButtonStates(),
                            states = listOf(selectedRejectButtonStates()),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    handler.handleInteraction(
                        headers = LinkedMultiValueMap(),
                        payload = "dummy-payload",
                    )

                    verify(exactly = 1) {
                        commandExecutor.execute(command = match<Command<*>> { it is ReplaceTextResponseCommand })
                    }
                    verify(exactly = 0) {
                        commandExecutor.execute(command = match<Command<*>> { it is InteractionCommand })
                    }
                }
            }
        }

        given("handleInteraction for a MEETING_DECLINE_REASON submission with Other and a blank detail") {
            `when`("handler processes the payload") {
                then("returns response_action errors targeting the detail block and skips execution") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
                            currentAction = selectedApplyButtonStates(),
                            states =
                                listOf(
                                    States(
                                        type = ActionElementTypes.STATIC_SELECT,
                                        isSelected = true,
                                        selectedValue = RejectReason.OTHER.name,
                                    ),
                                    States(
                                        type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                        isSelected = true,
                                        selectedValue = "   ",
                                    ),
                                ),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    val ackBody =
                        handler.handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")
                            ?: error("expected a response_action ack body")

                    ackBody shouldContain "\"response_action\":\"errors\""
                    ackBody shouldContain DeclineReasonModalIds.DETAIL_BLOCK_ID
                    verify(exactly = 0) { commandExecutor.execute(command = any<Command<*>>()) }
                }
            }
        }

        given("handleInteraction for a MEETING_DECLINE_REASON submission with Other and a filled detail") {
            `when`("handler processes the payload") {
                then("returns null and executes the persistence command") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
                            currentAction = selectedApplyButtonStates(),
                            states =
                                listOf(
                                    States(
                                        type = ActionElementTypes.STATIC_SELECT,
                                        isSelected = true,
                                        selectedValue = RejectReason.OTHER.name,
                                    ),
                                    States(
                                        type = ActionElementTypes.PLAIN_TEXT_INPUT,
                                        isSelected = true,
                                        selectedValue = "Visiting family abroad",
                                    ),
                                ),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    val ackBody =
                        handler.handleInteraction(headers = LinkedMultiValueMap(), payload = "dummy-payload")

                    ackBody.shouldBeNull()
                    verify(exactly = 1) {
                        commandExecutor.execute(command = match<Command<*>> { it is InteractionCommand })
                    }
                }
            }
        }

        given("handleInteraction for APPLY on MEETING_APPROVAL_REQUEST") {
            `when`("handler processes the payload") {
                then("routes APPLY through InteractionCommand, not the legacy path") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
                            currentAction = selectedApplyButtonStates(),
                            states = listOf(selectedApplyButtonStates()),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    handler.handleInteraction(
                        headers = LinkedMultiValueMap(),
                        payload = "dummy-payload",
                    )

                    verify(exactly = 1) {
                        commandExecutor.execute(command = match<Command<*>> { it is InteractionCommand })
                    }
                    verify(exactly = 0) {
                        commandExecutor.execute(command = match<Command<*>> { it is ReplaceTextResponseCommand })
                    }
                }
            }
        }

        given("handleInteraction for REJECT on MEETING_APPROVAL_REQUEST") {
            `when`("handler processes the payload") {
                then("routes REJECT through InteractionCommand, not the legacy path") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
                            currentAction = selectedRejectButtonStates(),
                            states = listOf(selectedRejectButtonStates()),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    handler.handleInteraction(
                        headers = LinkedMultiValueMap(),
                        payload = "dummy-payload",
                    )

                    verify(exactly = 1) {
                        commandExecutor.execute(command = match<Command<*>> { it is InteractionCommand })
                    }
                    verify(exactly = 0) {
                        commandExecutor.execute(command = match<Command<*>> { it is ReplaceTextResponseCommand })
                    }
                }
            }
        }

        given("handleInteraction for REJECT on a context-routed type (MEETING_CREATE_REQUEST)") {
            `when`("handler processes the payload") {
                then("commandExecutor routes the reject through an InteractionCommand, not the legacy path") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                            currentAction = selectedRejectButtonStates(),
                            states = listOf(selectedRejectButtonStates()),
                            idempotencyKey = UUID.randomUUID(),
                        )
                    every { payloadParser.parseStringPayload(payload = any()) } returns payload

                    handler.handleInteraction(
                        headers = LinkedMultiValueMap(),
                        payload = "dummy-payload",
                    )

                    verify(exactly = 1) {
                        commandExecutor.execute(command = match<Command<*>> { it is InteractionCommand })
                    }
                    verify(exactly = 0) {
                        commandExecutor.execute(command = match<Command<*>> { it is ReplaceTextResponseCommand })
                    }
                }
            }
        }
    })
