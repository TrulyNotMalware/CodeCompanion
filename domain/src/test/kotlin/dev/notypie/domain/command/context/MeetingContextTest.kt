package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_USER
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
import dev.notypie.domain.command.selectedApplyButtonStates
import dev.notypie.domain.command.selectedDatePickerStates
import dev.notypie.domain.command.selectedMultiUserSelectStates
import dev.notypie.domain.command.selectedPlainTextStates
import dev.notypie.domain.command.selectedTimePickerStates
import dev.notypie.domain.common.event.ActionEventPayloadContents
import dev.notypie.domain.common.event.GetMeetingEventPayload
import dev.notypie.domain.common.event.GetMeetingListEvent
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SendSlackMessageEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalTime

const val VALID_TEST_TITLE = "Test Meeting Title"
const val VALID_TEST_REASON = "Test Reason"

class MeetingContextTest :
    BehaviorSpec(body = {

        val eventBuilder = mockk<SlackEventBuilder>(relaxed = false)
        val eventQueue = createDomainEventQueue()

        every {
            eventBuilder.requestMeetingFormRequest(
                commandBasicInfo = any(),
                commandType = any(),
                commandDetailType = any(),
            )
        } returns createSendSlackMessageEvent(commandDetailType = CommandDetailType.REQUEST_MEETING_FORM)

        every {
            eventBuilder.simpleEphemeralTextRequest(
                commandBasicInfo = any(),
                textMessage = any(),
                commandType = any(),
                commandDetailType = any(),
            )
        } returns createSendSlackMessageEvent(commandDetailType = CommandDetailType.SIMPLE_TEXT)

        every {
            eventBuilder.replaceOriginalText(
                markdownText = any(),
                responseUrl = any(),
                commandBasicInfo = any(),
                commandDetailType = any(),
                commandType = any(),
            )
        } returns
            createSendSlackMessageEvent(
                isPostEventPayload = false,
                commandDetailType = CommandDetailType.REPLACE_TEXT,
            )

        val testCommandBasicInfo = createCommandBasicInfo()
        given("Meeting Context with no sub command") {
            val noSubCommandContext =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    subCommand = SubCommand(subCommandDefinition = NoSubCommands()),
                )

            `when`("runCommand() with no sub command") {
                val result = noSubCommandContext.runCommand()

                then("should return success result and create meeting context event") {
                    val event = noSubCommandContext.events.poll()
                    result.ok shouldBe true
                    eventQueue.size shouldBe 0
                    event shouldNotBe null
                    event?.type shouldBe CommandDetailType.REQUEST_MEETING_FORM
                    event?.name shouldBe SendSlackMessageEvent::class.java.simpleName
                    event?.payload?.javaClass shouldBe PostEventPayloadContents::class.java
                }
                eventQueue.flushQueue()
            }
        }

        given("Meeting Context with LIST sub command") {

            val listSubCommandContext =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    subCommand = SubCommand(subCommandDefinition = MeetingSubCommandDefinition.LIST),
                )

            `when`("run command with LIST sub command") {
                val result = listSubCommandContext.runCommand()

                then("should return success result and get meeting list event") {
                    val event = listSubCommandContext.events.poll()
                    result.ok shouldBe true
                    eventQueue.size shouldBe 0 // Only one event is created.
                    event shouldNotBe null
                    event?.name shouldBe GetMeetingListEvent::class.java.simpleName // shouldBe GetMeetingListEvent
                    event?.type shouldBe CommandDetailType.GET_MEETING_LIST
                    event?.payload?.javaClass shouldBe GetMeetingEventPayload::class.java
                    event?.isInternal shouldBe true
                }
                eventQueue.flushQueue()
            }
        }

        given("Meeting Context with interactionPayload") {

            val context =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    subCommand = SubCommand(subCommandDefinition = NoSubCommands()),
                )

            `when`("handleInteraction with successful data") {
                val interactionPayload =
                    createInteractionPayloadInput(
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                        currentAction = selectedApplyButtonStates(),
                        states =
                            listOf(
                                selectedPlainTextStates(text = VALID_TEST_TITLE),
                                selectedPlainTextStates(text = VALID_TEST_REASON),
                                selectedDatePickerStates(
                                    date = LocalDate.now().plusDays(1),
                                    format = RequestMeetingContext.DATE_PATTERN,
                                ),
                                selectedTimePickerStates(
                                    time = LocalTime.now(),
                                    format = RequestMeetingContext.SIMPLE_TIME_PATTERN,
                                ),
                                selectedMultiUserSelectStates(user = TEST_USER, maximumSequence = 10),
                            ),
                    )
                val result = context.handleInteraction(interactionPayload = interactionPayload)
                then("should return success result and create meeting context event") {
                    val event = eventQueue.poll()
                    result.ok shouldBe true
                    eventQueue.size shouldBe 0 // Only one event is created.
                    event shouldNotBe null
                    event?.type shouldBe CommandDetailType.REPLACE_TEXT
                    event?.name shouldBe SendSlackMessageEvent::class.java.simpleName
                    event?.payload?.javaClass shouldBe ActionEventPayloadContents::class.java
                }
            }
        }
    })
