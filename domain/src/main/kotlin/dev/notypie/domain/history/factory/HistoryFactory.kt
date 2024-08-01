package dev.notypie.domain.history.factory

import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.history.entity.History

class HistoryFactory private constructor(){
    companion object{
        fun buildHistory(requestType: String, slackApiResponse: SlackApiResponse) =
            History(
                apiAppId = slackApiResponse.apiAppId, channel = slackApiResponse.channel,
                commandType = slackApiResponse.commandType, ok = slackApiResponse.ok,
                token = slackApiResponse.token, states = slackApiResponse.actionStates,
                type = requestType, publisherId = slackApiResponse.publisherId, idempotencyKey = slackApiResponse.idempotencyKey
            )
    }
}