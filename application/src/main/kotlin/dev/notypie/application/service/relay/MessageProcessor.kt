package dev.notypie.application.service.relay

/**
 * Marker for the single outbox relay reader in the context (DB poll, Debezium CDC, future Kafka
 * consumer, …). Carries no method on purpose: each impl's entry point is typed to its own source
 * (`PollingMessageProcessor.pollPending()` on a schedule, `DebeziumLogTailingProcessor.consume(Envelope)`
 * on a Kafka listener), so no impl narrows a shared parameter at runtime. The interface exists for
 * bean selection — the polling bean is created only in POLLING mode, and its
 * `@ConditionalOnMissingBean(MessageProcessor::class)` additionally backs off when another
 * processor bean was already registered.
 */
interface MessageProcessor
