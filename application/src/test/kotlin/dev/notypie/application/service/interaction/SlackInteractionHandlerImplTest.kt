package dev.notypie.application.service.interaction

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.domain.command.selectedApplyButtonStates
import dev.notypie.domain.command.selectedRejectButtonStates
import dev.notypie.impl.command.InteractionPayloadParser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
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
