package dev.notypie.exception

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {  }

class StdoutErrorBroadcaster: ErrorBroadcaster {
    override fun broadcastError(message: String) {
        logger.error { message }
    }
}