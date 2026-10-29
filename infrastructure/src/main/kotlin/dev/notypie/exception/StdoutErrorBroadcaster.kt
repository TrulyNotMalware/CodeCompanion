package dev.notypie.exception

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class StdoutErrorBroadcaster : ErrorBroadcaster {
    override fun broadcastError(message: String) {
        log.error { message }
    }
}
