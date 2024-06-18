package dev.notypie.domain.command.dto

import java.util.concurrent.ConcurrentHashMap

class SlackRequestHeaders(
    private val underlying: Map<String, List<String>> = ConcurrentHashMap()
) {

    init{
        underlying.mapKeys { it.key.lowercase() }
    }

    fun getKeys() = underlying.keys
    fun getValues(key: String): List<String> = underlying.getValue(key)
}