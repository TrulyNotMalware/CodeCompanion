package dev.notypie.domain.dto

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.history.entity.Status

fun CommandOutput.isEmpty(): Boolean =
    apiAppId.isEmpty() &&
        publisherId.isEmpty() &&
        channel.isEmpty() &&
        commandDetailType == CommandDetailType.NOTHING && commandType == CommandType.SIMPLE && !ok &&
        status == Status.DO_NOTHING && errorReason.isEmpty()

infix fun CommandOutput.isSame(expected: CommandOutput): Boolean =
    apiAppId == expected.apiAppId &&
        publisherId == expected.publisherId &&
        channel == expected.channel &&
        commandDetailType == expected.commandDetailType &&
        status == expected.status &&
        errorReason == expected.errorReason &&
        idempotencyKey == expected.idempotencyKey &&
        ok == expected.ok

infix fun CommandOutput.shouldMatchExpected(expected: TestValidationData) =
    apiAppId == expected.commandBasicInfo.appId &&
        publisherId == expected.commandBasicInfo.publisherId &&
        channel == expected.commandBasicInfo.channel &&
        status == Status.SUCCESS &&
        errorReason.isEmpty() &&
        idempotencyKey == expected.commandBasicInfo.idempotencyKey &&
        ok && commandDetailType == expected.commandDetailType &&
        commandType == expected.commandType

data class TestValidationData(
    val commandDetailType: CommandDetailType,
    val commandType: CommandType,
    val commandBasicInfo: CommandBasicInfo,
)
