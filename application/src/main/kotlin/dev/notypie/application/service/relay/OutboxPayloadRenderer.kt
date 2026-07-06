package dev.notypie.application.service.relay

import dev.notypie.impl.command.OutboundRenderer
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion

/**
 * Turns a stored outbox row into a ready-to-send transport payload at deliver time. Shared by both
 * relay readers (polling + CDC) so the schema guard, envelope decode, and transport→renderer lookup
 * live in exactly one place.
 *
 * The schema guard is enforced here (before decode) so a relay binary that cannot reason about a
 * future payload shape refuses to send rather than emitting a malformed request — the same
 * contract the old row-side `toSlackEvent` held.
 */
class OutboxPayloadRenderer(
    private val renderers: Map<Transport, OutboundRenderer>,
) {
    fun render(row: OutboxMessage): SlackEventPayload {
        require(row.schemaVersion in OutboxSchemaVersion.SUPPORTED) {
            "Unsupported outbox schemaVersion=${row.schemaVersion} eventId=${row.eventId}. " +
                "Supported versions: ${OutboxSchemaVersion.SUPPORTED}. " +
                "Refusing to dispatch a payload this binary cannot reason about."
        }
        val transport = Transport.valueOf(row.transport)
        val renderer =
            renderers[transport]
                ?: error("No renderer registered for transport=$transport eventId=${row.eventId}")
        val envelope = OutboundMessageCodec.decode(json = row.payload)
        return renderer.render(message = envelope.message, basicInfo = envelope.basicInfo)
    }
}
