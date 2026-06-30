package dev.notypie.application.service.interaction

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.domain.command.selectedApplyButtonStates
import dev.notypie.domain.command.selectedRejectButtonStates
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.impl.command.InteractionPayloadParser
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
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

/**
 * Unit tests for [SlackInteractionHandlerImpl].
 *
 * We lock down the legacy whitelist constant so that new `CommandDetailType`
 * values cannot accidentally inherit the global "Canceled." short-circuit
 * without explicit opt-in, and exercise the primary execution paths of
 * `handleInteraction` end-to-end through a mocked executor/parser.
 */
class SlackInteractionHandlerImplTest :
    BehaviorSpec({
        val payloadParser = mockk<InteractionPayloadParser>()
        val applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val commandExecutor = mockk<CommandExecutor>(relaxed = true)
        val handler =
            SlackInteractionHandlerImpl(
                interactionPayloadParser = payloadParser,
                applicationEventPublisher = applicationEventPublisher,
                commandExecutor = commandExecutor,
            )

        given("legacy whitelist constant") {
            `when`("LEGACY_AUTO_REJECT_TYPES is inspected") {
                then("contains exactly REQUEST_APPLY_FORM and APPROVAL_FORM") {
                    SlackInteractionHandlerImpl.LEGACY_AUTO_REJECT_TYPES shouldBe
                        setOf(
                            CommandDetailType.REQUEST_APPLY_FORM,
                            CommandDetailType.APPROVAL_FORM,
                        )
                }

                then("does NOT contain new CommandDetailType values that need context routing") {
                    SlackInteractionHandlerImpl.LEGACY_AUTO_REJECT_TYPES shouldNotContainAnyOf
                        setOf(
                            CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                            CommandDetailType.REQUEST_MEETING_FORM,
                            CommandDetailType.NOTICE_FORM,
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
                            commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
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

        given("handleInteraction for REJECT on a legacy type (APPROVAL_FORM)") {
            `when`("handler processes the payload") {
                then("commandExecutor executes the legacy ReplaceTextResponseCommand") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.APPROVAL_FORM,
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

        given("handleInteraction for a DECLINE_REASON_MODAL submission with Other and a blank detail") {
            `when`("handler processes the payload") {
                then("returns response_action errors targeting the detail block and skips execution") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
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

        given("handleInteraction for a DECLINE_REASON_MODAL submission with Other and a filled detail") {
            `when`("handler processes the payload") {
                then("returns null and executes the persistence command") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
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

        given("handleInteraction for APPLY on MEETING_APPROVAL_NOTICE_FORM") {
            `when`("handler processes the payload") {
                then("routes APPLY through InteractionCommand, not the legacy path") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
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

        given("handleInteraction for REJECT on MEETING_APPROVAL_NOTICE_FORM") {
            `when`("handler processes the payload") {
                then("routes REJECT through InteractionCommand, not the legacy path") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
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

        given("handleInteraction for REJECT on a context-routed type (REQUEST_MEETING_FORM)") {
            `when`("handler processes the payload") {
                then("commandExecutor routes the reject through an InteractionCommand, not the legacy path") {
                    clearMocks(payloadParser, commandExecutor, applicationEventPublisher)
                    val payload =
                        createInteractionPayloadInput(
                            commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
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
