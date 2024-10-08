package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.response.SlackApiResponse

//Aggregate Root
abstract class Command(
    val idempotencyKey: String,
    val commandData: SlackCommandData,
    val slackApiRequester: SlackApiRequester,
) {

    abstract fun handleEvent(): SlackApiResponse
}