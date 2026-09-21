<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test/kotlin/dev/notypie/application/common

## Purpose
Specs for the idempotency-key primitives in `application/common`: deterministic UUID derivation from an
`IdempotencyData` payload plus a one-second time bucket, and the Jackson-backed SHA-256 serializer behind it.

## Key Files
| File | Description |
|------|-------------|
| `IdempotencyCreatorTest.kt` | `IdempotencyCreator.create(data, currentTimeMillis)` and `DefaultIdempotencyDataSerializer.serialize`. Same data + same 1 s bucket (`millis / 1000`) → same UUID; 999 ms vs 1000 ms → different; nested field whose type is not `java.io.Serializable` still hashes (Jackson, not JDK serialization); real `createMentionInboundCommand()` is stable across two calls (regression for seed jitter); `createInteractionInboundCommand()` and `createSlashInboundCommand(triggerId)` (nested `@JvmInline TriggerHandle`) serialize cleanly; serializer output is 64 lowercase hex chars. Plain Kotest `BehaviorSpec`, no mocks. |

The file also declares three package-level helper data classes (`TestIdempotencyData`, `NonSerializableInner`,
`NestedNonSerializableIdempotencyData`) rather than placing them in `testFixtures/` — they are only meaningful
to this spec.

## For AI Agents

### Working In This Directory
- Time is an explicit `currentTimeMillis` argument; never call `System.currentTimeMillis()` in a case here —
  two calls straddling a second boundary produce different keys and the spec becomes flaky.
- The "different time window" cases are the contract that lets Slack retries within the same second collapse to
  one key; if the bucket size changes, update both the boundary cases and the mention/slash regression cases.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.common.*'
./gradlew :application:test --tests 'dev.notypie.application.common.IdempotencyCreatorTest'
```
Fixtures used: `domain` testFixtures `command/InboundCommandCreator.kt` (`createMentionInboundCommand`,
`createInteractionInboundCommand`, `createSlashInboundCommand`).

### Common Patterns
- `given` per API surface (`create` with `String`, `create` with `IdempotencyData`, the serializer), `when` per
  input shape, `then` asserting equality or inequality of two derived keys.
- Regression cases name the bug they guard in the `then` description.

## Dependencies

### Internal
- `application/common/IdempotencyCreator.kt`, `DefaultIdempotencyDataSerializer`
- `domain/common/IdempotencyData`, `domain/command/inbound/*`

### External
Kotest assertions only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
