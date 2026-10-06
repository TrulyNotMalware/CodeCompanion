package dev.notypie.application.service.relay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Only the after-image is read. Debezium's schema, source and timing metadata vary with the connector, its version
// and the converter settings; modelling them as required fields failed every record into the dead-letter topic.
@JsonIgnoreProperties(ignoreUnknown = true)
data class Envelope(
    val payload: Payload,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Payload(
    val op: String? = null,
    val after: Map<String, Any>? = null,
)
