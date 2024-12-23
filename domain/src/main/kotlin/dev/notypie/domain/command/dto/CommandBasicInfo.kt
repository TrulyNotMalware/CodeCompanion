package dev.notypie.domain.command.dto

import java.util.*

data class CommandBasicInfo(
    val appId: String,
    val appToken: String,
    val publisherId: String,
    val channel: String,
    val idempotencyKey: String,
)

fun CommandBasicInfo.withNewKey(): CommandBasicInfo =
    CommandBasicInfo(appId, appToken, publisherId, channel, UUID.randomUUID().toString())