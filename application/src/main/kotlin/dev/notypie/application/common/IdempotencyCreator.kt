package dev.notypie.application.common

import java.security.MessageDigest
import java.util.UUID

object IdempotencyCreator {

    fun create(data: Any): String = UUID.nameUUIDFromBytes(data.toString().toByteArray()).toString()
    fun create(requestBody: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(requestBody.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}