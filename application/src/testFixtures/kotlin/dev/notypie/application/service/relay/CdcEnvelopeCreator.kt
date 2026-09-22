package dev.notypie.application.service.relay

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.repository.outbox.schema.MessageStatus
import java.util.UUID

fun createOutboxAfterImage(
    eventId: String = UUID.randomUUID().toString(),
    idempotencyKey: String = UUID.randomUUID().toString(),
    status: MessageStatus = MessageStatus.PENDING,
    publisherId: String = TEST_USER_ID,
    payload: String = "{}",
): Map<String, Any> =
    mapOf(
        "event_id" to eventId,
        "idempotency_key" to idempotencyKey,
        "publisher_id" to publisherId,
        "transport" to "SLACK",
        "payload" to payload,
        "status" to status.name,
        "version" to 0L,
        "schema_version" to 2,
        "created_at" to 1_777_000_000_000_000L,
    )

fun createCdcEnvelope(
    after: Map<String, Any>? = createOutboxAfterImage(),
    before: Map<String, Any>? = null,
    op: String = "c",
): Envelope =
    Envelope(
        schema = Schema(type = "struct", optional = false, name = "outbox.Envelope", version = 1),
        payload =
            Payload(
                before = before,
                after = after,
                source =
                    Source(
                        version = "3.0.0.Final",
                        connector = "mariadb",
                        name = "cdc",
                        db = "code_companion",
                        table = "outbox_message",
                        timeMillisecond = 0L,
                        timeMicrosecond = 0L,
                        timeNanosecond = 0L,
                        serverId = 1L,
                        file = "binlog.000001",
                        pos = 0,
                        row = 0,
                    ),
                op = op,
                timeMillisecond = 0L,
                timeMicrosecond = 0L,
                timeNanosecond = 0L,
            ),
    )
