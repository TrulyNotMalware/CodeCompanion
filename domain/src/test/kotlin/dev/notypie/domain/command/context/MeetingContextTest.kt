package dev.notypie.domain.command.context

import dev.notypie.domain.TEST_USER
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createIntentQueue
import dev.notypie.domain.command.createInteractionPayloadInput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.form.RequestMeetingContext
import dev.notypie.domain.command.entity.slash.MeetingListRange
import dev.notypie.domain.command.entity.slash.MeetingSubCommandDefinition
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.selectedApplyButtonStates
import dev.notypie.domain.command.selectedDatePickerStates
import dev.notypie.domain.command.selectedMultiUserSelectStates
import dev.notypie.domain.command.selectedPlainTextStates
import dev.notypie.domain.command.selectedTimePickerStates
import dev.notypie.domain.history.entity.Status
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

const val VALID_TEST_TITLE = "Test Meeting Title"
const val VALID_TEST_REASON = "Test Reason"

class MeetingContextTest :
    BehaviorSpec(body = {
        val testCommandBasicInfo = createCommandBasicInfo()

        given("Meeting Context with no sub command") {
            val intentQueue = createIntentQueue()
            val noSubCommandContext =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                    intents = intentQueue,
                )

            `when`("runCommand with no sub command") {
                val result = noSubCommandContext.runCommand()

                then("should return success result and create MeetingForm intent") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    result.commandType shouldBe CommandType.PIPELINE
                    result.commandDetailType shouldBe CommandDetailType.REQUEST_MEETING_FORM

                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.MeetingForm>()
                }
            }
        }

        given("Meeting Context with LIST sub command and no options") {
            val intentQueue = createIntentQueue()
            val listSubCommandContext =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand = SubCommand(subCommandDefinition = MeetingSubCommandDefinition.LIST),
                    intents = intentQueue,
                )

            `when`("run command with empty options") {
                val nowBefore = LocalDateTime.now()
                val result = listSubCommandContext.runCommand()
                val nowAfter = LocalDateTime.now()

                then("should emit MeetingListRequest intent with DEFAULT (WEEK) range") {
                    result.ok shouldBe true
                    result.status shouldBe Status.SUCCESS
                    result.commandType shouldBe CommandType.PIPELINE
                    result.commandDetailType shouldBe CommandDetailType.REQUEST_MEETING_FORM

                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val listIntent = intents.first().shouldBeInstanceOf<CommandIntent.MeetingListRequest>()
                    listIntent.publisherId shouldBe testCommandBasicInfo.publisherId
                    // DEFAULT is WEEK → endDate ≈ startDate + 7d (within 1-second window around "now")
                    ChronoUnit.SECONDS
                        .between(nowBefore, listIntent.startDate)
                        .shouldBeLessThanOrEqual(ChronoUnit.SECONDS.between(nowBefore, nowAfter) + 1)
                    ChronoUnit.DAYS
                        .between(listIntent.startDate, listIntent.endDate) shouldBe 7L
                }
            }
        }

        given("Meeting Context with LIST sub command and valid range tokens") {
            listOf(
                MeetingListRange.TODAY.token to MeetingListRange.TODAY,
                MeetingListRange.TOMORROW.token to MeetingListRange.TOMORROW,
                MeetingListRange.WEEK.token to MeetingListRange.WEEK,
                MeetingListRange.MONTH.token to MeetingListRange.MONTH,
            ).forEach { (tokenValue, expectedRange) ->
                `when`("run command with options=[\"$tokenValue\"]") {
                    val intentQueue = createIntentQueue()
                    val context =
                        RequestMeetingContext(
                            commandBasicInfo = testCommandBasicInfo,
                            subCommand =
                                SubCommand(
                                    subCommandDefinition = MeetingSubCommandDefinition.LIST,
                                    options = listOf(tokenValue),
                                ),
                            intents = intentQueue,
                        )
                    val before = LocalDateTime.now()
                    val result = context.runCommand()
                    val after = LocalDateTime.now()

                    then("should emit MeetingListRequest with $expectedRange range") {
                        result.ok shouldBe true
                        val intents = intentQueue.snapshot()
                        intents.size shouldBe 1
                        val intent = intents.first().shouldBeInstanceOf<CommandIntent.MeetingListRequest>()
                        val (expectedStart, expectedEnd) = expectedRange.dateRange(now = before)
                        val (expectedStartAfter, expectedEndAfter) = expectedRange.dateRange(now = after)
                        // startDate/endDate must fall between dateRange(before) and dateRange(after)
                        (intent.startDate in expectedStart..expectedStartAfter) shouldBe true
                        (intent.endDate in expectedEnd..expectedEndAfter) shouldBe true
                    }
                }
            }
        }

        given("Meeting Context with LIST sub command and blank option (e.g. double space)") {
            val intentQueue = createIntentQueue()
            val context =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand =
                        SubCommand(
                            subCommandDefinition = MeetingSubCommandDefinition.LIST,
                            options = listOf(""),
                        ),
                    intents = intentQueue,
                )

            `when`("run command is called") {
                val result = context.runCommand()

                then("should fall back to DEFAULT range (blank filtered out)") {
                    result.ok shouldBe true
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val intent = intents.first().shouldBeInstanceOf<CommandIntent.MeetingListRequest>()
                    ChronoUnit.DAYS.between(intent.startDate, intent.endDate) shouldBe 7L
                }
            }
        }

        given("Meeting Context with LIST sub command and unknown range token") {
            val intentQueue = createIntentQueue()
            val context =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand =
                        SubCommand(
                            subCommandDefinition = MeetingSubCommandDefinition.LIST,
                            options = listOf("yesterday"),
                        ),
                    intents = intentQueue,
                )

            `when`("run command is called") {
                val result = context.runCommand()

                then("should fail and emit EphemeralResponse with usage hint") {
                    result.ok shouldBe false
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val intent = intents.first().shouldBeInstanceOf<CommandIntent.EphemeralResponse>()
                    intent.message.contains("Unknown range 'yesterday'") shouldBe true
                    intent.message.contains("today | tomorrow | week | month") shouldBe true
                    // targetUserId must stay null: chat.postEphemeral `channel` needs a channel ID
                    intent.targetUserId shouldBe null
                }
            }
        }

        given("Meeting Context with LIST sub command and too many arguments") {
            val intentQueue = createIntentQueue()
            val context =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand =
                        SubCommand(
                            subCommandDefinition = MeetingSubCommandDefinition.LIST,
                            options = listOf("today", "extra"),
                        ),
                    intents = intentQueue,
                )

            `when`("run command is called") {
                val result = context.runCommand()

                then("should fail and emit EphemeralResponse for too many args") {
                    result.ok shouldBe false
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val intent = intents.first().shouldBeInstanceOf<CommandIntent.EphemeralResponse>()
                    intent.message.contains("Too many arguments") shouldBe true
                }
            }
        }

        given("Meeting Context with interactionPayload") {
            val intentQueue = createIntentQueue()
            val context =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                    intents = intentQueue,
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
                then("should return success result and create ReplaceMessage intent") {
                    res.ok shouldBe true
                    res.status shouldBe Status.SUCCESS
                    res.commandType shouldBe CommandType.PIPELINE
                    res.commandDetailType shouldBe CommandDetailType.REQUEST_MEETING_FORM

                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                }
            }
        }

        given("Meeting Context with interactionPayload including end time") {
            `when`("end time is after start time") {
                val intentQueue = createIntentQueue()
                val context =
                    RequestMeetingContext(
                        commandBasicInfo = testCommandBasicInfo,
                        subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                        intents = intentQueue,
                    )
                val meetingDate = LocalDate.now().plusDays(1)
                val startTime = LocalTime.of(10, 0)
                val endTime = LocalTime.of(11, 30)
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
                                    date = meetingDate,
                                    format = RequestMeetingContext.DATE_PATTERN,
                                ),
                                selectedTimePickerStates(
                                    time = startTime,
                                    format = RequestMeetingContext.SIMPLE_TIME_PATTERN,
                                ),
                                selectedTimePickerStates(
                                    time = endTime,
                                    format = RequestMeetingContext.SIMPLE_TIME_PATTERN,
                                ),
                                selectedMultiUserSelectStates(user = TEST_USER, maximumSequence = 10),
                            ),
                    )
                val res = context.handleInteraction(interactionPayload = interactionPayload)

                then("succeeds and emits ReplaceMessage") {
                    res.ok shouldBe true
                    res.status shouldBe Status.SUCCESS
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                }
            }

            `when`("end time is not after start time") {
                val intentQueue = createIntentQueue()
                val context =
                    RequestMeetingContext(
                        commandBasicInfo = testCommandBasicInfo,
                        subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                        intents = intentQueue,
                    )
                val meetingDate = LocalDate.now().plusDays(1)
                val sameTime = LocalTime.of(10, 0)
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
                                    date = meetingDate,
                                    format = RequestMeetingContext.DATE_PATTERN,
                                ),
                                selectedTimePickerStates(
                                    time = sameTime,
                                    format = RequestMeetingContext.SIMPLE_TIME_PATTERN,
                                ),
                                selectedTimePickerStates(
                                    time = sameTime,
                                    format = RequestMeetingContext.SIMPLE_TIME_PATTERN,
                                ),
                                selectedMultiUserSelectStates(user = TEST_USER, maximumSequence = 10),
                            ),
                    )
                val res = context.handleInteraction(interactionPayload = interactionPayload)

                then("fails with End time must be after start time ephemeral") {
                    res.ok shouldBe false
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    val errorIntent = intents.first().shouldBeInstanceOf<CommandIntent.EphemeralResponse>()
                    errorIntent.message shouldBe "End time must be after start time."
                }
            }

            `when`("no end time is selected") {
                val intentQueue = createIntentQueue()
                val context =
                    RequestMeetingContext(
                        commandBasicInfo = testCommandBasicInfo,
                        subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                        intents = intentQueue,
                    )
                val meetingDate = LocalDate.now().plusDays(1)
                val startTime = LocalTime.of(10, 0)
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
                                    date = meetingDate,
                                    format = RequestMeetingContext.DATE_PATTERN,
                                ),
                                selectedTimePickerStates(
                                    time = startTime,
                                    format = RequestMeetingContext.SIMPLE_TIME_PATTERN,
                                ),
                                selectedMultiUserSelectStates(user = TEST_USER, maximumSequence = 10),
                            ),
                    )
                val res = context.handleInteraction(interactionPayload = interactionPayload)

                then("succeeds — end time defaults to startAt + 1h in the domain entity") {
                    res.ok shouldBe true
                    res.status shouldBe Status.SUCCESS
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.ReplaceMessage>()
                }
            }
        }

        given("Meeting Context with invalid interaction payload (missing participants)") {
            val intentQueue = createIntentQueue()
            val context =
                RequestMeetingContext(
                    commandBasicInfo = testCommandBasicInfo,
                    subCommand = SubCommand.of(definition = MeetingSubCommandDefinition.NONE),
                    intents = intentQueue,
                )

            `when`("handleInteraction is called with no participants") {
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
                            ),
                    )
                val res = context.handleInteraction(interactionPayload = interactionPayload)

                then("should return fail result and emit EphemeralResponse intent (no ReplaceMessage, no Meeting)") {
                    res.ok shouldBe false
                    val intents = intentQueue.snapshot()
                    intents.size shouldBe 1
                    intents.first().shouldBeInstanceOf<CommandIntent.EphemeralResponse>()
                    val errorIntent = intents.first() as CommandIntent.EphemeralResponse
                    errorIntent.message shouldBe "Select participants"
                }
            }
        }
    })
