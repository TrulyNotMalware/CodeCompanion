package dev.notypie.repository.outbox.schema

/**
 * Single source of truth for the outbox-row payload schema version. Every persisted
 * [OutboxMessage] carries a [OutboxMessage.schemaVersion] equal to [CURRENT] at write time;
 * the reader side validates against [SUPPORTED] before decoding so a relay binary that
 * does not know how to parse a payload shape refuses to send a malformed request — better a
 * stuck row that the health indicator surfaces than a silent corruption.
 *
 * Bump [CURRENT] *and* extend [SUPPORTED] when introducing a new payload shape. Drop a
 * version from [SUPPORTED] only after a guaranteed-drained migration window, never before.
 */
object OutboxSchemaVersion {
    /**
     * V2 stores a codec-encoded transport-neutral [dev.notypie.repository.outbox.OutboundEnvelope]
     * in the single `payload` column and renders it to a wire payload at deliver time. V1 (a
     * pre-rendered Slack body split across payload/metadata/type columns) is no longer supported:
     * the outbox is drained big-bang, so there is no mixed-version window to straddle.
     */
    const val V2: Int = 2

    /** Schema version this binary writes for new outbox rows. */
    const val CURRENT: Int = V2

    /**
     * Versions that the relay can decode. Kept narrow on purpose — see the class-level KDoc for
     * the rationale.
     */
    val SUPPORTED: Set<Int> = setOf(V2)
}
