<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/test/kotlin/dev/notypie/common

## Purpose
Unit specs for the context-free helpers in main `common/`: the JSON column converter and the Kafka partition
key derivation. Both are plain Kotest specs with no Spring context and no mocks.

## Key Files
| File | Description |
|------|-------------|
| `JPAJsonConverterTest.kt` | `JPAJsonConverter`. `convertToDatabaseColumn`: non-empty map → compact JSON, empty map → `{}`, nested maps, and `null` → the literal string `"null"` (pinned, not an empty column). `convertToEntityAttribute`: valid JSON, `{}`, nested objects come back as `Map`, and `null` → empty map. One round-trip case. Plain `BehaviorSpec`. |
| `PartitionKeyUtilTest.kt` | `PartitionKeyUtil.createPartitionKey`. `Int` overload: positive (`10` → `4`), negative (`-10` → `4`, never negative), zero, determinism. `String` overload: result in `[0, 6)`, determinism, a 100-key sweep staying in range, empty string. Plain `BehaviorSpec`. |

## For AI Agents

### Working In This Directory
- The partition count `6` is hard-coded in the assertions. Changing the modulus in `PartitionKeyUtil` means
  updating the expected values here, not loosening them to range checks only.
- `convertToDatabaseColumn(null)` returning `"null"` is asserted behaviour; if the converter is changed to
  emit SQL `NULL`, update the case and check every `Map<String, Any>` column's nullability.
- `jsonMapper` itself (the third file in main `common/`) has no dedicated spec; its configuration is exercised
  indirectly through the converter and through `repository/outbox/OutboundMessageCodecTest`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.common.*'
```

### Common Patterns
`given` per method, `` `when` `` per input shape, `then` per assertion; `shouldBeInstanceOf` for the
`Map` results, exact string comparison for serialized JSON (key order is deterministic in `jsonMapper`).

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/common/` — `JPAJsonConverter`, `PartitionKeyUtil`, `jsonMapper`

### External
Kotest assertions only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
