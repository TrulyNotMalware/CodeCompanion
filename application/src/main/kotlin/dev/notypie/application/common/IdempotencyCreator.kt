package dev.notypie.application.common

import java.util.UUID

object IdempotencyCreator {

    fun create(data: Any): String = UUID.nameUUIDFromBytes(data.toString().toByteArray()).toString()
}