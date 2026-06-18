package dev.notypie.application.service.relay

/**
 * Fetches PENDING outbox rows from the source the impl is bound to (DB poll, Debezium CDC,
 * future Kafka consumer, …) and routes them downstream for relay. Each impl interprets the
 * [MessageProcessorParameter] subtype that matches its source — the polling impl ignores
 * input via [NoParameter], the CDC impl deserializes Debezium's `Envelope` payload.
 */
interface MessageProcessor {
    fun getPendingMessages(messageParameter: MessageProcessorParameter)
}

/**
 * Sealed root of the per-source input parameter for [MessageProcessor.getPendingMessages].
 * Subtypes live in the same package because Kotlin requires sealed-hierarchy members to
 * share a package.
 */
sealed class MessageProcessorParameter

/** Placeholder param for impls that poll on a schedule and need no caller-supplied input. */
object NoParameter : MessageProcessorParameter()
