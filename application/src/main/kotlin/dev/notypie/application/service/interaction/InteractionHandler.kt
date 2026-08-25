package dev.notypie.application.service.interaction

import org.springframework.util.MultiValueMap

interface InteractionHandler {
    /**
     * Handles a Slack interaction payload. Returns a `view_submission` ack body (the
     * `response_action` JSON, e.g. inline validation `errors`) when the submission must be
     * answered synchronously, or null for the normal empty ack. Callers relay the body to Slack:
     * the HTTP controller as the 200 response body, the Socket Mode receiver inside the ack.
     */
    fun handleInteraction(headers: MultiValueMap<String, String>, payload: String): String?
}
