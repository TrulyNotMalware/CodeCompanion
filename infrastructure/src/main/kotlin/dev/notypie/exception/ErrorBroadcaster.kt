package dev.notypie.exception

interface ErrorBroadcaster {
    fun broadcastError(message: String)
}