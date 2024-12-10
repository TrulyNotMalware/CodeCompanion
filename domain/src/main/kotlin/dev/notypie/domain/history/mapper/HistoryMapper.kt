package dev.notypie.domain.history.mapper

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.history.entity.History
import java.util.*

fun mapHistory(requestType: String, slackApiResponse: CommandOutput) =
    History(
        historyId = UUID.fromString(slackApiResponse.idempotencyKey),
        apiAppId = slackApiResponse.apiAppId, channel = slackApiResponse.channel,
        commandType = slackApiResponse.commandType, status = slackApiResponse.status,
        publisherId = slackApiResponse.publisherId, commandDetailType = slackApiResponse.commandDetailType
    )