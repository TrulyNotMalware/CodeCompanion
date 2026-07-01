package dev.notypie.domain.command.dto.response

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import java.util.UUID

open class CommandOutput(
    open val ok: Boolean,
    val apiAppId: String,
    open val status: Status,
    val commandDetailType: CommandDetailType,
    val idempotencyKey: UUID,
    val publisherId: String,
    val channel: String,
    val token: String = "", // FIXME ChatPostRequest doesn't have any token?
    val commandType: CommandType,
    val errorReason: String = "",
    val messageTs: String = "",
) {
    companion object {
        fun empty() =
            CommandOutput(
                ok = false,
                apiAppId = "",
                status = Status.DO_NOTHING,
                channel = "",
                commandType = CommandType.SIMPLE,
                commandDetailType = CommandDetailType.NOTHING,
                idempotencyKey = UUID.randomUUID(),
                publisherId = "",
            )

        fun fail(
            basicInfo: CommandBasicInfo,
            commandDetailType: CommandDetailType,
            reason: String,
            commandType: CommandType = CommandType.SIMPLE,
        ) = CommandOutput(
            ok = false,
            apiAppId = basicInfo.appId,
            status = Status.FAILED,
            channel = basicInfo.channel,
            commandType = commandType,
            commandDetailType = commandDetailType,
            idempotencyKey = basicInfo.idempotencyKey,
            publisherId = basicInfo.publisherId,
            errorReason = reason,
        )

        fun success(basicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType) =
            CommandOutput(
                ok = true,
                apiAppId = basicInfo.appId,
                status = Status.SUCCESS,
                channel = basicInfo.channel,
                commandType = commandType,
                commandDetailType = commandDetailType,
                idempotencyKey = basicInfo.idempotencyKey,
                publisherId = basicInfo.publisherId,
            )
    }
}
