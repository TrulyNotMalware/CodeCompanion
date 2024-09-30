package dev.notypie.domain.history.mapper

import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.history.entity.History
import java.util.*

fun mapHistory(requestType: String, slackApiResponse: SlackApiResponse) =
    History(
        historyId = UUID.fromString(slackApiResponse.idempotencyKey),
        apiAppId = slackApiResponse.apiAppId, channel = slackApiResponse.channel,
        commandType = slackApiResponse.commandType, status = slackApiResponse.status,
        token = slackApiResponse.token, states = slackApiResponse.actionStates,
        type = requestType, publisherId = slackApiResponse.publisherId
    )