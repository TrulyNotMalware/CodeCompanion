package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import java.util.UUID

const val TEST_APP_ID = "A12ABCDEFG" // starts with A
const val TEST_USER_ID = "U012ABCDEFG" // starts with U
const val TEST_CHANNEL = "C012ABCDEFG" // starts with C
const val TEST_TOKEN = "I_AM_TEST_TOKEN"

fun createCommandBasicInfo(
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL,
    idempotencyKey: UUID = UUID.randomUUID()
) = CommandBasicInfo(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    channel = channel,
    idempotencyKey = idempotencyKey
)