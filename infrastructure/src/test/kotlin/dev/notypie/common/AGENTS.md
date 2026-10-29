<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-27 -->

# infrastructure/src/test/kotlin/dev/notypie/common

## Purpose
Unit specs for the failure-boundary helpers in main `common/`: a plain Kotest spec with no Spring context and no mocks.

## Key Files
| File | Description |
|------|-------------|
| `ExceptionBoundaryTest.kt` | `containFailure` and `runCatchingExceptions`. Each: a normal block (ran, nothing reported / the value as success); an `Exception` (handed to `onFailure` / returned as the failure, nothing escapes); an `Error` (`StackOverflowError`, `NotImplementedError`) → the same instance escapes and `onFailure` never runs; an `InterruptedException` → the same instance rethrown with `Thread.interrupted()` true afterwards. Plain `BehaviorSpec`. |

## For AI Agents

### Working In This Directory
- `jsonMapper` itself has no dedicated spec; its configuration is exercised indirectly through
  `repository/outbox/OutboundMessageCodecTest`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.common.*'
```

### Common Patterns
`given` per helper, `` `when` `` per input shape, `then` per assertion.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/common/` — `jsonMapper`,
  `containFailure`, `runCatchingExceptions`

### External
Kotest assertions only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
