package dev.notypie.impl.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_TEAM_DOMAIN
import dev.notypie.domain.TEST_TEAM_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.ButtonType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class SlackInteractionRequestParserTest :
    BehaviorSpec({
        val parser = SlackInteractionRequestParser()

        given("parseStringPayload") {
            `when`("payload contains a primary button action") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        buttonType = ButtonType.PRIMARY,
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("should parse basic fields correctly") {
                    result.apiAppId shouldBe TEST_APP_ID
                    result.token shouldBe TEST_TOKEN
                    result.channel.id shouldBe TEST_CHANNEL_ID
                    result.channel.name shouldBe TEST_CHANNEL_NAME
                    result.team.id shouldBe TEST_TEAM_ID
                    result.team.domain shouldBe TEST_TEAM_DOMAIN
                    result.user.id shouldBe TEST_USER_ID
                    result.user.userName shouldBe TEST_USER_NAME
                    result.responseUrl shouldBe TEST_BASE_URL
                }

                then("should parse command detail type from message text") {
                    result.type shouldBe CommandDetailType.APPROVAL_FORM
                    result.idempotencyKey shouldBe idempotencyKey.toString()
                }

                then("current action should be APPLY_BUTTON") {
                    result.currentAction.type shouldBe ActionElementTypes.APPLY_BUTTON
                    result.currentAction.isSelected shouldBe true
                }
            }

            `when`("payload contains a danger button action") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        buttonType = ButtonType.DANGER,
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("current action should be REJECT_BUTTON") {
                    result.currentAction.type shouldBe ActionElementTypes.REJECT_BUTTON
                    result.currentAction.isSelected shouldBe true
                }
            }

            `when`("payload contains a button with no style") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                        actions =
                            buttonActionJsonWithoutStyle(
                                value = "$idempotencyKey, ${CommandDetailType.SIMPLE_TEXT}",
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("current action should be BUTTON") {
                    result.currentAction.type shouldBe ActionElementTypes.BUTTON
                    result.currentAction.isSelected shouldBe true
                }
            }

            `when`("payload is ephemeral with primary button") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        commandDetailType = CommandDetailType.APPROVAL_FORM,
                        isEphemeral = true,
                        buttonType = ButtonType.PRIMARY,
                        buttonValue = "$idempotencyKey, ${CommandDetailType.APPROVAL_FORM}",
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("should use button value for idempotency key and type") {
                    result.idempotencyKey shouldBe idempotencyKey.toString()
                    result.type shouldBe CommandDetailType.APPROVAL_FORM
                }

                then("container should be ephemeral") {
                    result.container.isEphemeral shouldBe true
                }

                then("botId should come from apiAppId for ephemeral") {
                    result.botId shouldBe TEST_APP_ID
                }
            }

            `when`("payload is ephemeral with non-primary action") {
                val payload =
                    createBlockActionPayloadJson(
                        isEphemeral = true,
                        actions = multiStaticSelectActionJson(selectedValues = listOf("opt1")),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("type should be NOTHING for non-primary ephemeral action") {
                    result.type shouldBe CommandDetailType.NOTHING
                }
            }

            `when`("payload contains multi_static_select state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry =
                                    multiStaticSelectStateJson(
                                        selectedOptions = listOf("A" to "a", "B" to "b"),
                                    ),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain MULTI_STATIC_SELECT with selected values") {
                    val state = result.states.first { it.type == ActionElementTypes.MULTI_STATIC_SELECT }
                    state.isSelected shouldBe true
                    state.selectedValue shouldBe "a, b"
                }
            }

            `when`("payload contains empty multi_static_select state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = multiStaticSelectStateJson(selectedOptions = emptyList()),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain unselected MULTI_STATIC_SELECT") {
                    val state = result.states.first { it.type == ActionElementTypes.MULTI_STATIC_SELECT }
                    state.isSelected shouldBe false
                }
            }

            `when`("payload contains multi_users_select state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = multiUsersSelectStateJson(selectedUsers = listOf("U001", "U002")),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain MULTI_USERS_SELECT with joined user ids") {
                    val state = result.states.first { it.type == ActionElementTypes.MULTI_USERS_SELECT }
                    state.isSelected shouldBe true
                    state.selectedValue shouldBe "U001,U002"
                }
            }

            `when`("payload contains plain_text_input state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = plainTextInputStateJson(value = "user input text"),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain PLAIN_TEXT_INPUT with value") {
                    val state = result.states.first { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
                    state.isSelected shouldBe true
                    state.selectedValue shouldBe "user input text"
                }
            }

            `when`("payload contains datepicker state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = datepickerStateJson(selectedDate = "2026-04-01"),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain DATE_PICKER with selected date") {
                    val state = result.states.first { it.type == ActionElementTypes.DATE_PICKER }
                    state.isSelected shouldBe true
                    state.selectedValue shouldBe "2026-04-01"
                }
            }

            `when`("payload contains timepicker state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = timepickerStateJson(selectedTime = "14:30"),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain TIME_PICKER with selected time") {
                    val state = result.states.first { it.type == ActionElementTypes.TIME_PICKER }
                    state.isSelected shouldBe true
                    state.selectedValue shouldBe "14:30"
                }
            }

            `when`("payload contains two timepicker entries in the same block") {
                val stateValues =
                    """{"block_1":{""" +
                        """"action_1":${timepickerStateJson(selectedTime = "10:00")},""" +
                        """"action_2":${timepickerStateJson(selectedTime = "11:30")}""" +
                        """}}"""
                val payload = createBlockActionPayloadJson(stateValues = stateValues)

                val result = parser.parseStringPayload(payload = payload)
                val timePickers = result.states.filter { it.type == ActionElementTypes.TIME_PICKER }

                then("both TIME_PICKERs are preserved in insertion order (start, end)") {
                    timePickers.size shouldBe 2
                    timePickers[0].selectedValue shouldBe "10:00"
                    timePickers[1].selectedValue shouldBe "11:30"
                }
            }

            `when`("payload contains an empty timepicker state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = """{"type":"timepicker","selected_time":null}""",
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("TIME_PICKER state is not selected and carries an empty value") {
                    val state = result.states.first { it.type == ActionElementTypes.TIME_PICKER }
                    state.isSelected shouldBe false
                    state.selectedValue shouldBe ""
                }
            }

            `when`("payload contains checkboxes state with selections") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry =
                                    checkboxesStateJson(
                                        selectedOptions = listOf("Option A" to "a", "Option B" to "b"),
                                    ),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain CHECKBOX with selected option texts") {
                    val state = result.states.first { it.type == ActionElementTypes.CHECKBOX }
                    state.isSelected shouldBe true
                    state.selectedValue shouldBe "Option A, Option B"
                }
            }

            `when`("payload contains empty checkboxes state") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues =
                            stateValuesJson(
                                stateEntry = checkboxesStateJson(selectedOptions = emptyList()),
                            ),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain unselected CHECKBOX") {
                    val state = result.states.first { it.type == ActionElementTypes.CHECKBOX }
                    state.isSelected shouldBe false
                }
            }

            `when`("payload contains unknown state type") {
                val payload =
                    createBlockActionPayloadJson(
                        stateValues = stateValuesJson(stateEntry = unknownStateJson()),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("states should contain UNKNOWN type") {
                    val state = result.states.first()
                    state.type shouldBe ActionElementTypes.UNKNOWN
                }
            }

            `when`("payload contains multi_users_select action") {
                val payload =
                    createBlockActionPayloadJson(
                        actions = multiUsersSelectActionJson(selectedUsers = listOf("U001", "U002")),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("current action should be MULTI_USERS_SELECT") {
                    result.currentAction.type shouldBe ActionElementTypes.MULTI_USERS_SELECT
                    result.currentAction.isSelected shouldBe true
                    result.currentAction.selectedValue shouldBe "U001, U002"
                }
            }

            `when`("payload contains multi_static_select action") {
                val payload =
                    createBlockActionPayloadJson(
                        actions = multiStaticSelectActionJson(selectedValues = listOf("opt1", "opt2")),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("current action should be MULTI_STATIC_SELECT") {
                    result.currentAction.type shouldBe ActionElementTypes.MULTI_STATIC_SELECT
                    result.currentAction.isSelected shouldBe true
                    result.currentAction.selectedValue shouldBe "opt1, opt2"
                }
            }

            `when`("payload contains unrecognized action type") {
                val payload =
                    createBlockActionPayloadJson(
                        actions = unknownActionJson(),
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("current action should be UNKNOWN") {
                    result.currentAction.type shouldBe ActionElementTypes.UNKNOWN
                }
            }

            `when`("payload is invalid JSON") {
                then("should throw JsonSyntaxException") {
                    shouldThrow<com.google.gson.JsonSyntaxException> {
                        parser.parseStringPayload(payload = "{ invalid json }")
                    }
                }
            }

            `when`("message text contains invalid CommandDetailType") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        messageText = "$idempotencyKey, INVALID_TYPE",
                    )

                then("should fall back to NOTHING instead of throwing (graceful degrade)") {
                    val result = parser.parseStringPayload(payload = payload)
                    result.type shouldBe CommandDetailType.NOTHING
                    result.idempotencyKey shouldBe idempotencyKey.toString()
                    result.routingExtras shouldBe emptyList()
                }
            }

            `when`("message text contains routing extras (3rd token meetingId)") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        messageText = "$idempotencyKey,${CommandDetailType.SIMPLE_TEXT},42",
                    )

                then("routingExtras should expose the meetingId token") {
                    val result = parser.parseStringPayload(payload = payload)
                    result.type shouldBe CommandDetailType.SIMPLE_TEXT
                    result.routingExtras shouldBe listOf("42")
                }
            }

            `when`("message text is only an idempotencyKey (1 token)") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        messageText = idempotencyKey.toString(),
                    )

                then("type falls back to NOTHING, no throw") {
                    val result = parser.parseStringPayload(payload = payload)
                    result.idempotencyKey shouldBe idempotencyKey.toString()
                    result.type shouldBe CommandDetailType.NOTHING
                    result.routingExtras shouldBe emptyList()
                }
            }
        }

        given("parseStringPayload for view_submission") {
            `when`("a decline-reason modal submission arrives with a selected radio value") {
                val meetingKey = UUID.randomUUID()
                val participantUserId = "U_PARTICIPANT_A"
                val payload =
                    createDeclineReasonViewSubmissionJson(
                        meetingIdempotencyKey = meetingKey,
                        participantUserId = participantUserId,
                        selectedReason = "HEALTH_ISSUE",
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("routing type is recovered from private_metadata, not message text") {
                    result.type shouldBe CommandDetailType.DECLINE_REASON_MODAL
                    result.idempotencyKey shouldBe meetingKey.toString()
                    result.routingExtras shouldBe listOf(participantUserId)
                    result.privateMetadata shouldBe
                        "$meetingKey,DECLINE_REASON_MODAL,$participantUserId"
                }

                then("currentAction is synthesized as APPLY_BUTTON so routing treats submission as primary") {
                    result.currentAction.type shouldBe ActionElementTypes.APPLY_BUTTON
                    result.currentAction.isSelected shouldBe true
                }

                then("dropdown selection is surfaced as a STATIC_SELECT state carrying the enum name") {
                    val selection =
                        result.states.single { it.type == ActionElementTypes.STATIC_SELECT }
                    selection.isSelected shouldBe true
                    selection.selectedValue shouldBe "HEALTH_ISSUE"
                }
            }

            `when`("a submission arrives with no radio selection") {
                val meetingKey = UUID.randomUUID()
                val payload =
                    createDeclineReasonViewSubmissionJson(
                        meetingIdempotencyKey = meetingKey,
                        participantUserId = "U_P",
                        selectedReason = "",
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("the STATIC_SELECT state marks isSelected=false with a blank value") {
                    val selection =
                        result.states.single { it.type == ActionElementTypes.STATIC_SELECT }
                    selection.isSelected shouldBe false
                    selection.selectedValue shouldBe ""
                }

                then("routing still resolves from private_metadata") {
                    result.type shouldBe CommandDetailType.DECLINE_REASON_MODAL
                }
            }

            `when`("a submission carries the 5-token private_metadata (Wave 2 chat.update format)") {
                val meetingKey = UUID.randomUUID()
                val participantUserId = "U_WAVE2"
                val noticeChannel = "C_NOTICE_WAVE2"
                val noticeMessageTs = "1700000000.000400"
                val payload =
                    createDeclineReasonViewSubmissionJson(
                        meetingIdempotencyKey = meetingKey,
                        participantUserId = participantUserId,
                        selectedReason = "HEALTH_ISSUE",
                        noticeChannel = noticeChannel,
                        noticeMessageTs = noticeMessageTs,
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("routingExtras surfaces participant + channel + messageTs in order") {
                    // DeclineReasonSubmissionContext reads by index: [0]=user, [1]=channel, [2]=ts.
                    result.routingExtras shouldBe listOf(participantUserId, noticeChannel, noticeMessageTs)
                    result.privateMetadata shouldBe
                        "$meetingKey,DECLINE_REASON_MODAL,$participantUserId," +
                        "$noticeChannel,$noticeMessageTs"
                }
            }
        }

        given("parseStringPayload for block_actions — Container.messageTs") {
            `when`("a non-ephemeral block_actions arrives with a message_ts") {
                val idempotencyKey = UUID.randomUUID()
                val payload =
                    createBlockActionPayloadJson(
                        idempotencyKey = idempotencyKey,
                        messageText = "$idempotencyKey,${CommandDetailType.MEETING_APPROVAL_NOTICE_FORM}",
                    )

                val result = parser.parseStringPayload(payload = payload)

                then("Container.messageTs carries the raw ts string so chat.update can use it later") {
                    // Wave 2: MeetingApprovalResponseContext pulls this into OpenDeclineReasonModal
                    // so the submission handler can chat.update the notice DM.
                    result.container.messageTs shouldBe "1234567890.123"
                }
            }
        }
    })
