package dev.notypie.repository.outbox.schema

/**
 * Single source of truth for the outbox-row payload schema version. Every persisted
 * [OutboxMessage] carries a [OutboxMessage.schemaVersion] equal to [CURRENT] at write time;
 * the reader side validates against [SUPPORTED] before dispatching so a relay binary that
 * does not know how to parse a future payload shape refuses to send a malformed Slack
 * request — better a stuck row that the health indicator surfaces than a silent corruption.
 *
 * Bump [CURRENT] *and* extend [SUPPORTED] when introducing a new payload shape. Drop a
 * version from [SUPPORTED] only after a guaranteed-drained migration window, never before.
 */
object OutboxSchemaVersion {
    const val V1: Int = 1

    /**
     * Schema version that this binary writes for new outbox rows. Phase 2 #9 establishes
     * v1 as the baseline; Phase 3 may bump this when the standup-bot event types need
     * payload fields that did not exist in the v1 layout.
     */
    const val CURRENT: Int = V1

    /**
     * Versions that the relay can deserialize. Kept narrow on purpose — see the class-level
     * KDoc for the rationale.
     */
    val SUPPORTED: Set<Int> = setOf(V1)
}
