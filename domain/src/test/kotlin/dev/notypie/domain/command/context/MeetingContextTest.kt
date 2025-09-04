package dev.notypie.domain.command.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.common.event.GetMeetingEventPayload
import dev.notypie.domain.common.event.GetMeetingListEvent
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SendSlackMessageEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk

class MeetingContextTest : BehaviorSpec(body = {

    val eventBuilder = mockk< SlackEventBuilder>(relaxed = true)
    val eventQueue = createDomainEventQueue()

    every {
        eventBuilder.requestMeetingFormRequest(
            commandBasicInfo = any(),
            commandType = any(),
            commandDetailType = any()
        )
    } returns createSendSlackMessageEvent(commandDetailType = CommandDetailType.REQUEST_MEETING_FORM)

    val testCommandBasicInfo = createCommandBasicInfo()
    given("Meeting Context with no sub command"){
        val noSubCommandContext = RequestMeetingContext(
            commandBasicInfo = testCommandBasicInfo,
            slackEventBuilder = eventBuilder,
            events = eventQueue,
            subCommand = SubCommand(subCommandDefinition=NoSubCommands())
        )

        `when`("runCommand() with no sub command"){
            val result = noSubCommandContext.runCommand()

            then("should return success result and create meeting context event"){
                val event = noSubCommandContext.events.poll()
                result.ok shouldBe true
                eventQueue.size shouldBe 0
                event shouldNotBe null
                event?.name shouldBe SendSlackMessageEvent::class.java.simpleName
                event?.payload?.javaClass shouldBe PostEventPayloadContents::class.java

            }
            eventQueue.flushQueue()
        }
    }

    given("Meeting Context with LIST sub command"){

        val eventQueue = createDomainEventQueue()
        val listSubCommandContext = RequestMeetingContext(
            commandBasicInfo = testCommandBasicInfo,
            slackEventBuilder = eventBuilder,
            events = eventQueue,
            subCommand = SubCommand(subCommandDefinition=MeetingSubCommandDefinition.LIST)
        )

        `when`("run command with LIST sub command"){
            val result = listSubCommandContext.runCommand()

            then("should return success result and get meeting list event"){
                val event = listSubCommandContext.events.poll()
                result.ok shouldBe true
                eventQueue.size shouldBe 0 // Only one event is created.
                event shouldNotBe null
                event?.name shouldBe GetMeetingListEvent::class.java.simpleName // shouldBe GetMeetingListEvent
                event?.payload?.javaClass shouldBe GetMeetingEventPayload::class.java
                event?.isInternal shouldBe true
            }
            eventQueue.flushQueue()
        }
    }

//    given("Meeting Context with interactionPayload"){
//        val interactionPayload = createInteractionPayloadInput(
//            commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
//            currentAction =
//        )
//        val context = RequestMeetingContext(
//            commandBasicInfo = testCommandBasicInfo,
//            slackEventBuilder = eventBuilder,
//            events = eventQueue,
//            subCommand = SubCommand(subCommandDefinition=NoSubCommands())
//        )
//    }
})