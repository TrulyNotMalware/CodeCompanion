package dev.notypie.domain.command.dto.response

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
    val actionStates: List<States> = listOf()
){
    companion object{
        fun empty() = CommandOutput(
            ok = true, apiAppId = "", status = Status.SUCCESS,
            channel = "", commandType = CommandType.SIMPLE,
            commandDetailType = CommandDetailType.NOTHING,
            idempotencyKey = "", publisherId = ""
        )
    }
}
