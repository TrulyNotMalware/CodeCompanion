package dev.notypie.application.service.relay

interface MessageRelayService {
    fun dispatchPendingMessages()
}