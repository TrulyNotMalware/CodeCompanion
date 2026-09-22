<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-22 | Updated: 2026-09-22 -->

# application/src/testFixtures/kotlin/dev/notypie/application/service/relay

## Purpose
Builds the Debezium `Envelope` that `DebeziumLogTailingProcessor.consume()` receives, so the CDC lane can be
specced without Kafka. The after-image is the raw column map (`event_id`, `idempotency_key`, `status`,
`created_at` as Debezium microseconds, ...) that `MutableMap.toOutboxMessage()` converts.

## Key Files
| File | Description |
|------|-------------|
| `CdcEnvelopeCreator.kt` | `createOutboxAfterImage(eventId, idempotencyKey, status = PENDING, publisherId, payload = "{}"): Map<String, Any>` and `createCdcEnvelope(after = createOutboxAfterImage(), before = null, op = "c"): Envelope` with a minimal `Schema` / `Source` |

## For AI Agents

### Working In This Directory
- `after = null` models a delete event, `op` is informational only — the processor keys off `after` and
  `status`, not `op`.
- Keep the map keys aligned with `OutboxMessage`'s `@JsonProperty` names; an unknown shape is exactly what
  the "records that can never be processed" spec relies on to raise `CdcRecordParseException`.

### Testing Requirements
Sole consumer: `application/src/test/kotlin/dev/notypie/application/service/relay/DebeziumLogTailingProcessorTest.kt`.

## Dependencies

### Internal
- `dev.notypie.application.service.relay.Envelope` and friends (`DebeziumOutboxMessage.kt`)
- `MessageStatus` from `:infrastructure`

### External
- `dev.notypie.domain.TEST_USER_ID` from `testFixtures(project(":domain"))`
