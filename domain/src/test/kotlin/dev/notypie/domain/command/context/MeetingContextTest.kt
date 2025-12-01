package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_USER
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createDomainEventQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.GetMeetingEventPayload
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.flushQueue
import dev.notypie.domain.command.mockEventBuilder
import dev.notypie.domain.command.selectedApplyButtonStates
import dev.notypie.domain.command.selectedDatePickerStates
import dev.notypie.domain.command.selectedMultiUserSelectStates
import dev.notypie.domain.command.selectedPlainTextStates
import dev.notypie.domain.command.selectedTimePickerStates
import dev.notypie.domain.dto.TestValidationData
import dev.notypie.domain.dto.shouldMatchExpected
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate
import java.time.LocalTime

const val VALID_TEST_TITLE = "Test Meeting Title"
const val VALID_TEST_REASON = "Test Reason"

class MeetingContextTest :
    BehaviorSpec(body = {
        val eventQueue = createDomainEventQueue()
        val testCommandBasicInfo = createCommandBasicInfo()
        val eventBuilder =
            mockEventBuilder {
                SlackEventBuilder::requestMeetingFormRequest returns
                    createSendSlackMessageEvent(
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
                        commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                    )
                SlackEventBuilder::simpleEphemeralTextRequest returns
                    createSendSlackMessageEvent(
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    )
                SlackEventBuilder::replaceOriginalText returns
                    createSendSlackMessageEvent(
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
                        isPostEventPayload = false,
                        commandDetailType = CommandDetailType.REPLACE_TEXT,
                    )
            }
        given("Meeting Context with no sub command") {
            val noSubCommandContext =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    slackEventBuilder = eventBuilder,
                    events = eventQueue,
                    subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                )

            `when`("runCommand with no sub command") {
                val result = noSubCommandContext.runCommand()

                then("should return success result and create meeting context event") {
                    val event = noSubCommandContext.events.poll()
                    val validationData =
                        TestValidationData(
                            commandDetailType = noSubCommandContext.commandDetailType,
                            commandType = noSubCommandContext.commandType,
                            commandBasicInfo = testCommandBasicInfo,
                        )
                    (result shouldMatchExpected validationData) shouldBe true
                    eventQueue.size shouldBe 0
                    event shouldNotBe null
                    event?.type shouldBe noSubCommandContext.commandDetailType
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
                    val validationData =
                        TestValidationData(
                            commandDetailType = listSubCommandContext.commandDetailType,
                            commandType = listSubCommandContext.commandType,
                            commandBasicInfo = testCommandBasicInfo,
                        )
                    (result shouldMatchExpected validationData) shouldBe true
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
                    subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                )

            `when`("handleInteraction with successful data") {
                val interactionPayload =
                    createInteractionPayloadInput(
                        idempotencyKey = testCommandBasicInfo.idempotencyKey,
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
                val res = context.handleInteraction(interactionPayload = interactionPayload)
                then("should return success result and create meeting context event") {
                    val event = eventQueue.poll()
                    val validationData =
                        TestValidationData(
                            commandDetailType = context.commandDetailType,
                            commandBasicInfo = testCommandBasicInfo,
                            commandType = context.commandType,
                        )
                    (res shouldMatchExpected validationData) shouldBe true
                    eventQueue.size shouldBe 0 // Only one event is created.
                    event shouldNotBe null
                    event?.type shouldBe CommandDetailType.REPLACE_TEXT
                    event?.name shouldBe SendSlackMessageEvent::class.java.simpleName
                    event?.payload?.javaClass shouldBe ActionEventPayloadContents::class.java
                }
            }
        }
    })
