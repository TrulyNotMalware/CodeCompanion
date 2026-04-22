package dev.notypie.impl.command

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.entity.event.SlackEventPayload
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.mockk
import io.mockk.verify

class SlackEventAsyncDispatcherTest :
    BehaviorSpec({

        given("SlackEventAsyncDispatcher wired with a mocked MessageDispatcher") {
            val messageDispatcher = mockk<MessageDispatcher>(relaxed = true)
            val dispatcher = SlackEventAsyncDispatcher(messageDispatcher = messageDispatcher)

            `when`("a SlackEventPayload is delivered to listenSlackEvent") {
                val event = mockk<SlackEventPayload>(relaxed = true)

                dispatcher.listenSlackEvent(event = event)

                then("it should delegate to MessageDispatcher.dispatch exactly once") {
                    verify(exactly = 1) { messageDispatcher.dispatch(event = event) }
                }
            }
        }
    })
