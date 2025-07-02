package dev.notypie.domain.command.dto

class SlackRequestHeaders(
    headers: Map<String, List<String>> = emptyMap()
) {
    private val underlying = headers.mapKeys { it.key.lowercase() }
}