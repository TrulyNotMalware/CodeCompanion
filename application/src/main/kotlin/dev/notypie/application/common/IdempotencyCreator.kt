package dev.notypie.application.common

import dev.notypie.common.objectMapper
import java.util.UUID

object IdempotencyCreator {

    fun create(data: Any): String = UUID.nameUUIDFromBytes(
        this.serializeData(data = data).toByteArray(charset = Charsets.UTF_8)
    ).toString()

    private fun serializeData(data: Any): String =
        when (data) {
            is String -> data
            is Number -> data.toString()
            is Boolean -> data.toString()
            else -> objectMapper.writeValueAsString(data)
        }
}