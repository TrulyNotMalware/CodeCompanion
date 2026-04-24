package dev.notypie.impl.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_BOT_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_TEAM_DOMAIN
import dev.notypie.domain.TEST_TEAM_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionTypes
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.templates.ButtonType
import dev.notypie.templates.DeclineReasonModalIds
import java.util.UUID

// ============ Action JSON Builders ============

fun buttonActionJson(
    buttonType: ButtonType = ButtonType.PRIMARY,
    value: String = "",
    actionId: String = "action_approve",
) =
    """[{"type":"${ActionElementTypes.BUTTON.elementName}","action_id":"$actionId","style":"${buttonType.name.lowercase()}","value":"$value"}]"""

fun buttonActionJsonWithoutStyle(value: String = "", actionId: String = "action_btn") =
    """[{"type":"${ActionElementTypes.BUTTON.elementName}","action_id":"$actionId","value":"$value"}]"""

fun multiStaticSelectActionJson(
    selectedValues: List<String>,
    actionId: String = ActionElementTypes.STATIC_SELECT.elementName,
) = """[{"type":"${ActionElementTypes.MULTI_STATIC_SELECT.elementName}","action_id":"$actionId","selected_options":[${
    selectedValues.joinToString(",") { """{"value":"$it"}""" }
}]}]"""

fun multiUsersSelectActionJson(selectedUsers: List<String>, actionId: String = "user_select") =
    """[{"type":"multi_users_select","action_id":"$actionId","selected_users":[${
        selectedUsers.joinToString(",") { """"$it"""" }
    }]}]"""

fun unknownActionJson(type: String = "overflow", actionId: String = "overflow_1") =
    """[{"type":"$type","action_id":"$actionId"}]"""

// ============ State Value JSON Builders ============

fun stateValuesJson(blockId: String = "block_1", actionId: String = "action_1", stateEntry: String) =
    """{"$blockId":{"$actionId":$stateEntry}}"""

fun multiStaticSelectStateJson(selectedOptions: List<Pair<String, String>>) =
    if (selectedOptions.isEmpty()) {
        """{"type":"${ActionElementTypes.MULTI_STATIC_SELECT.elementName}","selected_options":[]}"""
    } else {
        """{"type":"${ActionElementTypes.MULTI_STATIC_SELECT.elementName}","selected_options":[${
            selectedOptions.joinToString(",") { (text, value) ->
                """{"text":{"type":"plain_text","text":"$text"},"value":"$value"}"""
            }
        }]}"""
    }

fun multiUsersSelectStateJson(selectedUsers: List<String>) =
    if (selectedUsers.isEmpty()) {
        """{"type":"multi_users_select","selected_users":[]}"""
    } else {
        """{"type":"multi_users_select","selected_users":[${
            selectedUsers.joinToString(",") { """"$it"""" }
        }]}"""
    }

fun plainTextInputStateJson(value: String) = """{"type":"plain_text_input","value":"$value"}"""

fun datepickerStateJson(selectedDate: String) = """{"type":"datepicker","selected_date":"$selectedDate"}"""

fun timepickerStateJson(selectedTime: String) = """{"type":"timepicker","selected_time":"$selectedTime"}"""

fun checkboxesStateJson(selectedOptions: List<Pair<String, String>>) =
    if (selectedOptions.isEmpty()) {
        """{"type":"checkboxes","selected_options":[]}"""
    } else {
        """{"type":"checkboxes","selected_options":[${
            selectedOptions.joinToString(",") { (text, value) ->
                """{"text":{"type":"plain_text","text":"$text"},"value":"$value"}"""
            }
        }]}"""
    }

fun unknownStateJson(type: String = "some_unknown_type") = """{"type":"$type"}"""

// ============ View Submission Payload Builder ============

/**
 * Builds a minimal `view_submission` payload for the decline-reason modal. The block_id /
 * action_id constants match [dev.notypie.templates.ModalTemplateBuilder]'s companion object
 * so that the parser can locate the selected radio value.
 */
fun createDeclineReasonViewSubmissionJson(
    meetingIdempotencyKey: UUID,
    participantUserId: String,
    selectedReason: String,
    noticeChannel: String = "",
    noticeMessageTs: String = "",
    teamId: String = TEST_TEAM_ID,
    teamDomain: String = TEST_TEAM_DOMAIN,
    userId: String = TEST_USER_ID,
    userName: String = TEST_USER_NAME,
    appId: String = TEST_APP_ID,
    token: String = TEST_TOKEN,
): String {
    // Default to the legacy 3-token format so existing tests keep working; when the caller
    // supplies channel/ts, emit the 5-token Wave 2 format.
    val privateMetadata =
        if (noticeChannel.isBlank() && noticeMessageTs.isBlank()) {
            "$meetingIdempotencyKey,DECLINE_REASON_MODAL,$participantUserId"
        } else {
            "$meetingIdempotencyKey,DECLINE_REASON_MODAL,$participantUserId,$noticeChannel,$noticeMessageTs"
        }
    val stateValues =
        if (selectedReason.isNotBlank()) {
            """
            {
                "${DeclineReasonModalIds.BLOCK_ID}": {
                    "${DeclineReasonModalIds.ACTION_ID}": {
                        "type": "${ActionElementTypes.STATIC_SELECT.elementName}",
                        "selected_option": {
                            "text": {"type": "plain_text", "text": "$selectedReason"},
                            "value": "$selectedReason"
                        }
                    }
                }
            }
            """.trimIndent()
        } else {
            "{}"
        }
    return """
        {
            "type": "${InteractionTypes.VIEW_SUBMISSION}",
            "token": "$token",
            "api_app_id": "$appId",
            "trigger_id": "trigger_view_submission_123",
            "is_enterprise_install": false,
            "team": {"id": "$teamId", "domain": "$teamDomain"},
            "user": {
                "id": "$userId",
                "username": "$userName",
                "name": "$userName",
                "team_id": "$teamId"
            },
            "view": {
                "id": "V_TEST_123",
                "type": "modal",
                "callback_id": "${DeclineReasonModalIds.CALLBACK_ID}",
                "private_metadata": "$privateMetadata",
                "state": { "values": $stateValues }
            }
        }
        """.trimIndent()
}

// ============ Full Payload Builder ============

fun createBlockActionPayloadJson(
    idempotencyKey: UUID = UUID.randomUUID(),
    commandDetailType: CommandDetailType = CommandDetailType.APPROVAL_FORM,
    isEphemeral: Boolean = false,
    buttonType: ButtonType = ButtonType.PRIMARY,
    buttonValue: String? = null,
    messageText: String? = null,
    stateValues: String = "{}",
    actions: String? = null,
    appId: String = TEST_APP_ID,
    token: String = TEST_TOKEN,
    responseUrl: String = TEST_BASE_URL,
    teamId: String = TEST_TEAM_ID,
    teamDomain: String = TEST_TEAM_DOMAIN,
    userId: String = TEST_USER_ID,
    userName: String = TEST_USER_NAME,
    channelId: String = TEST_CHANNEL_ID,
    channelName: String = TEST_CHANNEL_NAME,
    botId: String = TEST_BOT_ID,
): String {
    val resolvedButtonValue = buttonValue ?: "$idempotencyKey, $commandDetailType"
    val resolvedMessageText = messageText ?: "$idempotencyKey, $commandDetailType"
    val resolvedActions =
        actions ?: buttonActionJson(buttonType = buttonType, value = resolvedButtonValue)

    val messageSection =
        if (isEphemeral) {
            ""
        } else {
            """
            "message": {
                "bot_id": "$botId",
                "type": "message",
                "text": "$resolvedMessageText",
                "ts": "1234567890.123"
            },
            """.trimIndent()
        }

    return """
        {
            "type": "${InteractionTypes.BLOCK_ACTIONS}",
            "token": "$token",
            "trigger_id": "trigger_123",
            "api_app_id": "$appId",
            "is_enterprise_install": false,
            "response_url": "$responseUrl",
            "team": {
                "id": "$teamId",
                "domain": "$teamDomain"
            },
            "user": {
                "id": "$userId",
                "username": "$userName",
                "name": "$userName",
                "team_id": "$teamId"
            },
            "channel": {
                "id": "$channelId",
                "name": "$channelName"
            },
            "container": {
                "type": "message",
                "message_ts": "1234567890.123",
                "is_ephemeral": $isEphemeral
            },
            $messageSection
            "state": {
                "values": $stateValues
            },
            "actions": $resolvedActions
        }
        """.trimIndent()
}
