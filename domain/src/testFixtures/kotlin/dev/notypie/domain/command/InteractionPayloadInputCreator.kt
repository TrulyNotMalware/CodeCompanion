package dev.notypie.domain.command

import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.Channel
import dev.notypie.domain.command.dto.interactions.Container
import dev.notypie.domain.command.dto.interactions.Enterprise
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.interactions.Team
import dev.notypie.domain.command.dto.interactions.User
import dev.notypie.domain.command.entity.CommandDetailType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

val TEST_USER = User(id = TEST_USER_ID, userName = TEST_USER_NAME, name = TEST_USER_NAME, teamId = TEST_TEAM_ID)
val TEST_TEAM = Team(id = TEST_TEAM_ID, domain = TEST_TEAM_DOMAIN)
val TEST_CHANNEL = Channel(id = TEST_CHANNEL_ID, name = TEST_CHANNEL_NAME)
const val SEPARATOR = ","

const val TEST_BASE_URL = "https://hooks.example.com/actions"

fun createTestContainer() =
    Container(
        type = "block_actions",
        isEphemeral = false,
        messageTime = Instant.now(),
    )

fun createInteractionPayloadInput(
    commandDetailType: CommandDetailType,
    currentAction: States,
    states: List<States>,
    user: User = TEST_USER,
    team: Team = TEST_TEAM,
    channel: Channel = TEST_CHANNEL,
    container: Container = createTestContainer(),
    enterprise: Enterprise? = null,
    apiAppId: String = TEST_APP_ID,
    botId: String = TEST_BOT_ID,
    token: String = TEST_TOKEN,
    responseUrl: String = TEST_BASE_URL,
    idempotencyKey: String = UUID.randomUUID().toString(),
    triggerId: String = "",
    isEnterprise: Boolean = false,
): InteractionPayload =
    InteractionPayload(
        type = commandDetailType,
        team = team,
        user = user,
        triggerId = triggerId,
        isEnterprise = isEnterprise,
        enterprise = enterprise,
        idempotencyKey = idempotencyKey,
        apiAppId = apiAppId,
        botId = botId,
        token = token,
        container = container,
        channel = channel,
        responseUrl = responseUrl,
        states = states,
        currentAction = currentAction,
    )

private fun ActionElementTypes.toStates(isSelected: Boolean = false, selectedValue: String = "") =
    States(
        type = this,
        isSelected = isSelected,
        selectedValue = selectedValue,
    )

fun selectedApplyButtonStates() = ActionElementTypes.APPLY_BUTTON.toStates(isSelected = true, selectedValue = "apply")

fun selectedRejectButtonStates() = ActionElementTypes.REJECT_BUTTON.toStates(isSelected = true)

fun selectedMultiUserSelectStates(selectedUsers: List<User>) =
    ActionElementTypes.MULTI_USERS_SELECT.toStates(
        isSelected = true,
        selectedValue = selectedUsers.joinToString(SEPARATOR) { user -> user.userName },
    )

fun selectedMultiUserSelectStates(user: User, maximumSequence: Int) =
    ActionElementTypes.MULTI_USERS_SELECT.toStates(
        isSelected = true,
        selectedValue =
            if (maximumSequence > 0) {
                (1..maximumSequence).joinToString(SEPARATOR) { "${user.userName}$it" }
            } else {
                user.userName
            },
    )

fun selectedPlainTextStates(text: String) =
    ActionElementTypes.PLAIN_TEXT_INPUT.toStates(isSelected = true, selectedValue = text)

fun selectedDatePickerStates(date: LocalDate, format: String): States {
    val formatted = date.format(DateTimeFormatter.ofPattern(format))
    return ActionElementTypes.DATE_PICKER.toStates(isSelected = true, selectedValue = formatted)
}

fun selectedTimePickerStates(time: LocalTime, format: String): States {
    val formatted = time.format(DateTimeFormatter.ofPattern(format))
    return ActionElementTypes.TIME_PICKER.toStates(isSelected = true, selectedValue = formatted)
}
