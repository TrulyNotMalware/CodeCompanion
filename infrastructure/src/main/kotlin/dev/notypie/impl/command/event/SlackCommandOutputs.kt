package dev.notypie.impl.command.event

import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
import dev.notypie.domain.command.entity.CommandType

fun failOutput(event: SlackEventPayload, reason: String) =
    CommandOutput(
        ok = false,
        apiAppId = event.apiAppId,
        status = Status.FAILED,
        channel = event.channel,
        commandType = CommandType.SIMPLE,
        commandDetailType = event.commandDetailType,
        idempotencyKey = event.idempotencyKey,
        publisherId = event.publisherId,
        errorReason = reason,
    )

fun successOutput(payload: SlackEventPayload, commandType: CommandType, messageTs: String = "") =
    CommandOutput(
        ok = true,
        apiAppId = payload.apiAppId,
        status = Status.SUCCESS,
        channel = payload.channel,
        commandType = commandType,
        commandDetailType = payload.commandDetailType,
        idempotencyKey = payload.idempotencyKey,
        publisherId = payload.publisherId,
        messageTs = messageTs,
    )
