package dev.notypie.domain.command

import dev.notypie.domain.command.dto.interactions.Channel
import dev.notypie.domain.command.dto.interactions.Container
import dev.notypie.domain.command.dto.interactions.Enterprise
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.interactions.Team
import dev.notypie.domain.command.dto.interactions.User
import dev.notypie.domain.command.entity.CommandDetailType
import java.time.Instant
import java.util.UUID

val TEST_USER = User(id = TEST_USER_ID, userName = TEST_USER_NAME, name = TEST_USER_NAME, teamId = TEST_TEAM_ID)
val TEST_TEAM = Team(id = TEST_TEAM_ID, domain = TEST_TEAM_DOMAIN)
val TEST_CHANNEL = Channel(id = TEST_CHANNEL_ID, name = TEST_CHANNEL_NAME)

const val TEST_BASE_URL = "https://hooks.example.com/actions"

fun createTestContainer() = Container(
    type = "block_actions",
    isEphemeral = false,
    messageTime = Instant.now()
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
    isEnterprise: Boolean = false
): InteractionPayload {
    return InteractionPayload(
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
        currentAction = currentAction
    )
}
