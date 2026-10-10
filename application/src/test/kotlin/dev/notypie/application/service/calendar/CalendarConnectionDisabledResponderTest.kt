package dev.notypie.application.service.calendar

import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createCalendarConnectionRequestEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CalendarConnectionAction
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class CalendarConnectionDisabledResponderTest :
    BehaviorSpec({
        given("calendar integration is disabled") {
            val staged = slot<OutboundMessage>()
            val stager =
                mockk<OutboundMessageStager> {
                    every { stage(message = capture(staged), basicInfo = any()) } returns mockk(relaxed = true)
                }
            val publisher = mockk<EventPublisher>(relaxed = true)
            val responder =
                CalendarConnectionDisabledResponder(
                    outboundStager = stager,
                    eventPublisher = publisher,
                    transactionManager = createStubTransactionManager(),
                )

            `when`("any calendar action arrives") {
                responder.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.STATUS))

                then("the requester alone is told the feature is off") {
                    val ephemeral = staged.captured.shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    ephemeral.target.id shouldBe TEST_CHANNEL_ID
                    ephemeral.recipient?.id shouldBe TEST_USER_ID
                    ephemeral.detailType shouldBe CommandDetailType.CALENDAR_CONNECTION
                    ephemeral.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        CalendarConnectionDisabledResponder.DISABLED_MESSAGE
                    verify(exactly = 1) { publisher.publishEvent(events = any()) }
                }
            }
        }
    })
