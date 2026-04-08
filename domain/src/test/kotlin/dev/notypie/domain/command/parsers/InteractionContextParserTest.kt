package dev.notypie.domain.command.parsers

import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createInteractionSlackCommandData
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import dev.notypie.domain.command.entity.context.form.ApprovalCallbackContext
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.parsers.InteractionCotextParser
import dev.notypie.domain.command.mockEventBuilder
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class InteractionContextParserTest :
    BehaviorSpec({
        val idempotencyKey = UUID.randomUUID()
        val slackEventBuilder = mockEventBuilder(relaxed = true) {}
        val events = createDomainEventQueue()

        given("parseContext") {
            `when`("interaction type is APPROVAL_FORM") {
                val commandData =
                    createInteractionSlackCommandData(
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        idempotencyKey = idempotencyKey,
                    )
                val parser =
                    InteractionCotextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        slackEventBuilder = slackEventBuilder,
                        events = events,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return SlackApprovalFormContext") {
                    result.shouldBeInstanceOf<SlackApprovalFormContext>()
                }
            }

            `when`("interaction type is NOTICE_FORM") {
                val commandData =
                    createInteractionSlackCommandData(
                        commandDetailType = CommandDetailType.NOTICE_FORM,
                        idempotencyKey = idempotencyKey,
                    )
                val parser =
                    InteractionCotextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        slackEventBuilder = slackEventBuilder,
                        events = events,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return ApprovalCallbackContext") {
                    result.shouldBeInstanceOf<ApprovalCallbackContext>()
                }
            }

            `when`("interaction type is REQUEST_MEETING_FORM") {
                val commandData =
                    createInteractionSlackCommandData(
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                        idempotencyKey = idempotencyKey,
                    )
                val parser =
                    InteractionCotextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        slackEventBuilder = slackEventBuilder,
                        events = events,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return RequestMeetingContext") {
                    result.shouldBeInstanceOf<RequestMeetingContext>()
                }
            }

            `when`("interaction type is SIMPLE_TEXT (falls to else branch)") {
                val commandData =
                    createInteractionSlackCommandData(
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        idempotencyKey = idempotencyKey,
                    )
                val parser =
                    InteractionCotextParser(
                        slackCommandData = commandData,
                        baseUrl = "",
                        commandId = UUID.randomUUID(),
                        idempotencyKey = idempotencyKey,
                        slackEventBuilder = slackEventBuilder,
                        events = events,
                    )

                val result = parser.parseContext(idempotencyKey = idempotencyKey)

                then("should return EmptyContext") {
                    result.shouldBeInstanceOf<EmptyContext>()
                }
            }
        }
    })
