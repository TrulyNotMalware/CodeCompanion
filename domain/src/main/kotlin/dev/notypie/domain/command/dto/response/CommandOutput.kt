package dev.notypie.domain.command.dto.response

import dev.notypie.domain.common.event.SlackEvent
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status
import java.util.UUID

open class CommandOutput(
    open val ok: Boolean,
    val apiAppId: String,
    open val status: Status,
    val commandDetailType: CommandDetailType,

    val idempotencyKey: UUID,
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
            idempotencyKey = UUID.randomUUID(), publisherId = ""
        )

        fun fail(event: SlackEvent, reason: String) = CommandOutput(
            ok = false, apiAppId = event.apiAppId, status = Status.FAILED,
            channel = event.channel, commandType = CommandType.SIMPLE,
            commandDetailType = CommandDetailType.NOTHING,
            idempotencyKey = event.idempotencyKey, publisherId = event.publisherId,
            errorReason = reason
        )

        fun success(event: SlackEvent) = CommandOutput(
            ok = true, apiAppId = event.apiAppId, status = Status.SUCCESS,
            channel = event.channel, commandType = CommandType.SIMPLE,
            commandDetailType = CommandDetailType.NOTHING,
            idempotencyKey = event.idempotencyKey, publisherId = event.publisherId
            )
    }
}
