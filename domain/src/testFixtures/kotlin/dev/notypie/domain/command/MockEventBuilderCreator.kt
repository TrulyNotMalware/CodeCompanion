package dev.notypie.domain.command

import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import io.mockk.every
import io.mockk.mockk
import kotlin.reflect.KFunction

fun mockEventBuilder(relaxed: Boolean = false, configure: MockEventBuilderCreator.() -> Unit): SlackEventBuilder =
    MockEventBuilderCreator().apply(configure).build(relaxed = relaxed)

class MockEventBuilderCreator {
    private val mocks = mutableMapOf<String, SendSlackMessageEvent>()

    infix fun KFunction<*>.returns(event: SendSlackMessageEvent) {
        mocks[name] = event
    }

    fun build(relaxed: Boolean = false): SlackEventBuilder {
        val eventBuilder = mockk<SlackEventBuilder>(relaxed = relaxed)

        mocks.forEach { (functionName, returnValue) ->
            when (functionName) {
                SlackEventBuilder::simpleTextRequest.name -> {
                    every {
                        eventBuilder.simpleTextRequest(
                            commandDetailType = any(),
                            headLineText = any(),
                            commandBasicInfo = any(),
                            simpleString = any(),
                            commandType = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::simpleEphemeralTextRequest.name -> {
                    every {
                        eventBuilder.simpleEphemeralTextRequest(
                            textMessage = any(),
                            commandBasicInfo = any(),
                            commandType = any(),
                            commandDetailType = any(),
                            targetUserId = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::detailErrorTextRequest.name -> {
                    every {
                        eventBuilder.detailErrorTextRequest(
                            commandDetailType = any(),
                            errorClassName = any(),
                            errorMessage = any(),
                            details = any(),
                            commandType = any(),
                            commandBasicInfo = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::simpleTimeScheduleRequest.name -> {
                    every {
                        eventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = any(),
                            headLineText = any(),
                            commandBasicInfo = any(),
                            timeScheduleInfo = any(),
                            commandType = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::simpleApplyRejectRequest.name -> {
                    every {
                        eventBuilder.simpleApplyRejectRequest(
                            commandDetailType = any(),
                            commandBasicInfo = any(),
                            approvalContents = any(),
                            commandType = any(),
                            targetUserId = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::simpleApprovalFormRequest.name -> {
                    every {
                        eventBuilder.simpleApprovalFormRequest(
                            commandDetailType = any(),
                            headLineText = any(),
                            commandBasicInfo = any(),
                            selectionFields = any(),
                            commandType = any(),
                            reasonInput = any(),
                            approvalContents = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::requestMeetingFormRequest.name -> {
                    every {
                        eventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = any(),
                            commandType = any(),
                            commandDetailType = any(),
                            approvalContents = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::getMeetingListFormRequest.name -> {
                    every {
                        eventBuilder.getMeetingListFormRequest(
                            myMeetings = any(),
                            commandBasicInfo = any(),
                            commandType = any(),
                            commandDetailType = any(),
                        )
                    } returns returnValue
                }

                SlackEventBuilder::replaceOriginalText.name -> {
                    every {
                        eventBuilder.replaceOriginalText(
                            markdownText = any(),
                            responseUrl = any(),
                            commandBasicInfo = any(),
                            commandType = any(),
                            commandDetailType = any(),
                        )
                    } returns returnValue
                }

                else -> {
                    error("Unknown function: $functionName. Add it to the when clause!")
                }
            }
        }

        return eventBuilder
    }
}
