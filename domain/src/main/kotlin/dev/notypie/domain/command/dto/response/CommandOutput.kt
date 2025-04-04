package dev.notypie.domain.command.dto.response

import dev.notypie.domain.command.dto.SlackEvent
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status

data class CommandOutput(
    val ok: Boolean,
    val apiAppId: String,
    val status: Status,
    val commandDetailType: CommandDetailType,

    val idempotencyKey: String,
    val publisherId: String,
    val channel: String,
    val token: String = "", //FIXME ChatPostRequest doesn't have any token?
    val commandType: CommandType,
    val actionStates: List<States> = listOf(),

    val errorReason: String = ""
){
    companion object{
        fun empty() = CommandOutput(
            ok = true, apiAppId = "", status = Status.SUCCESS,
            channel = "", commandType = CommandType.SIMPLE,
            commandDetailType = CommandDetailType.NOTHING,
            idempotencyKey = "", publisherId = ""
        )

        fun fail(event: SlackEvent, reason: String) = CommandOutput(
            ok = false, apiAppId = event.apiAppId, status = Status.FAILED,
            channel = event.channel, commandType = CommandType.SIMPLE,
            commandDetailType = CommandDetailType.NOTHING,
            idempotencyKey = event.idempotencyKey, publisherId = event.publisherId
        )

        fun success(event: SlackEvent) = CommandOutput(
            ok = true, apiAppId = event.apiAppId, status = Status.SUCCESS,
            channel = event.channel, commandType = CommandType.SIMPLE,
            commandDetailType = CommandDetailType.NOTHING,
            idempotencyKey = event.idempotencyKey, publisherId = event.publisherId
            )
    }
}
