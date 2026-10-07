package dev.notypie.application.service.relay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Envelope(
    val payload: Payload,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Payload(
    val op: String? = null,
    val after: Map<String, Any>? = null,
)
