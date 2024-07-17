package dev.notypie.impl.command

interface InteractionPayloadParser {
    fun parseStringContents(payload: String)
}