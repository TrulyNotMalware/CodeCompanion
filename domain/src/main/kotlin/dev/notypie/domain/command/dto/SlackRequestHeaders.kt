package dev.notypie.domain.command.dto

class SlackRequestHeaders(
    private val underlying: Map<String, List<String>>
) {

    init{
        underlying.mapKeys { it.key.lowercase() }
    }

    fun getKeys() = underlying.keys
    fun getValues(key: String): List<String> = underlying.getValue(key)
}