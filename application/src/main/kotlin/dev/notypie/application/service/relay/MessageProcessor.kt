package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.MessageProcessorParameter

interface MessageProcessor {

    fun getPendingMessages(messageParameter: MessageProcessorParameter)
}