package dev.notypie.impl.command.slack

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
import dev.notypie.domain.command.entity.CommandDetailType
import java.time.Instant
import java.util.UUID

private val TEST_USER =
    User(id = TEST_USER_ID, userName = TEST_USER_NAME, name = TEST_USER_NAME, teamId = TEST_TEAM_ID)
private val TEST_TEAM = Team(id = TEST_TEAM_ID, domain = TEST_TEAM_DOMAIN)
private val TEST_CHANNEL = Channel(id = TEST_CHANNEL_ID, name = TEST_CHANNEL_NAME)

fun createTestContainer() =
    Container(
        type = InteractionTypes.BLOCK_ACTIONS,
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
    idempotencyKey: UUID,
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
        idempotencyKey = idempotencyKey.toString(),
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
