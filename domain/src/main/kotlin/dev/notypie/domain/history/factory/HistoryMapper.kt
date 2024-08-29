package dev.notypie.domain.history.factory

import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.history.entity.History

class HistoryMapper private constructor(){
    companion object{
        fun map(requestType: String, slackApiResponse: SlackApiResponse) =
            History(
                apiAppId = slackApiResponse.apiAppId, channel = slackApiResponse.channel,
                commandType = slackApiResponse.commandType, status = slackApiResponse.status,
                token = slackApiResponse.token, states = slackApiResponse.actionStates,
                type = requestType, publisherId = slackApiResponse.publisherId, idempotencyKey = slackApiResponse.idempotencyKey
            )
    }
}