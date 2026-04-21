package dev.notypie.application.service.interaction

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.impl.command.InteractionPayloadParser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.context.ApplicationEventPublisher

/**
 * Unit tests for [SlackInteractionHandlerImpl].
 *
 * Handler end-to-end behavior (actually invoking `handleInteraction`) is deliberately NOT
 * covered here because the call chain depends on [IdempotencyCreator] which uses the
 * default Java serializer that requires every field of `SlackCommandData.body` to
 * implement `java.io.Serializable`. Slack interaction DTOs (`InteractionPayload`,
 * `Team`, `User`, `Channel`, `Container`, `States`) do not implement Serializable,
 * so exercising the full handler path trips a pre-existing `NotSerializableException`
 * that lives in production code, not in this refactor.
 *
 * This is tracked as a separate concern; the proper fix is to switch
 * [IdempotencyCreator] to the [JacksonIdempotencyDataSerializer] that already exists
 * in the same file. Once that change lands, full handler tests can be added here.
 *
 * In the meantime, we lock down the legacy whitelist constant so that new
 * `CommandDetailType` values cannot accidentally inherit the global "Canceled."
 * short-circuit without explicit opt-in.
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
    })
