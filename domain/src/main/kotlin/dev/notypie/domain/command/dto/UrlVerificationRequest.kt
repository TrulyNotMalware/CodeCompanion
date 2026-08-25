package dev.notypie.domain.command.dto

internal data class UrlVerificationRequest(
    val type: String,
    val channel: String,
    val token: String,
    val challenge: String,
)
