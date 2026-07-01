package dev.notypie.domain.command.parsers

import dev.notypie.domain.command.createInboundInteraction
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionResponseInboundCommand
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.ApprovalFormContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.MeetingApprovalResponseContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.parsers.InteractionContextParser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class InteractionContextParserTest :
    BehaviorSpec({
        val idempotencyKey = UUID.randomUUID()
        val intents = createIntentQueue()

        fun createParser(detailType: CommandDetailType): InteractionContextParser {
            val interaction =
                createInboundInteraction(
                    detailType = detailType,
                    idempotencyKey = idempotencyKey,
                )
            return InteractionContextParser(
                commandData = createInteractionResponseInboundCommand(interaction = interaction),
                interaction = interaction,
                baseUrl = "",
                commandId = UUID.randomUUID(),
                idempotencyKey = idempotencyKey,
                intents = intents,
            )
        }

        given("parseContext") {
            `when`("interaction type is APPROVAL_FORM") {
                val parser = createParser(detailType = CommandDetailType.APPROVAL_FORM)

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return ApprovalFormContext") {
                    result.shouldBeInstanceOf<ApprovalFormContext>()
                }
            }

            `when`("interaction type is NOTICE_FORM") {
                val parser = createParser(detailType = CommandDetailType.NOTICE_FORM)

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return ApprovalCallbackContext") {
                    result.shouldBeInstanceOf<ApprovalCallbackContext>()
                }
            }

            `when`("interaction type is REQUEST_MEETING_FORM") {
                val parser = createParser(detailType = CommandDetailType.REQUEST_MEETING_FORM)

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return RequestMeetingContext") {
                    result.shouldBeInstanceOf<RequestMeetingContext>()
                }
            }

            `when`("interaction type is MEETING_APPROVAL_NOTICE_FORM") {
                val parser = createParser(detailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM)

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return MeetingApprovalResponseContext") {
                    result.shouldBeInstanceOf<MeetingApprovalResponseContext>()
                }
            }

            `when`("interaction type is SIMPLE_TEXT (falls to else branch)") {
                val parser = createParser(detailType = CommandDetailType.SIMPLE_TEXT)

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return EmptyContext") {
                    result.shouldBeInstanceOf<EmptyContext>()
                }
            }
        }
    })
