package dev.notypie.repository.outbox

/**
 * Delivery channel an outbox row is destined for. Persisted as a plain string on the row so the
 * relay can select the matching renderer at deliver time. Only Slack exists today; new transports
 * add an enum constant and a renderer registered against it.
 */
enum class Transport {
    SLACK,
}
