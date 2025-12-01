package dev.notypie.application.common

import dev.notypie.common.jsonMapper
import java.util.UUID

object IdempotencyCreator {
    fun create(data: Any): UUID =
        UUID.nameUUIDFromBytes(serializeData(data = data).toByteArray(charset = Charsets.UTF_8))

    private fun serializeData(data: Any): String =
        when (data) {
            is String -> data
            is Number -> data.toString()
            is Boolean -> data.toString()
            else -> jsonMapper.writeValueAsString(data)
        }
}
