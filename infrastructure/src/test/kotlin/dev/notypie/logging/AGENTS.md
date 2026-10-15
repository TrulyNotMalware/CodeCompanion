<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-15 | Updated: 2026-10-15 -->

# infrastructure/src/test/kotlin/dev/notypie/logging

## Purpose
Spec for the shared log-capture fixture in `testFixtures/kotlin/dev/notypie/logging/`. The one test package that
mirrors a testFixtures package instead of a main one.

## Key Files
| File | Description |
|------|-------------|
| `LogCaptureTest.kt` | Plain `BehaviorSpec` on a logger name no other spec uses (`LogCaptureTest::class.java.name`). An action that logs WARN, ERROR, WARN and returns `42` → destructured value `42`, the three events in order, `messagesAt` / `eventsAt` filtering by level and carrying the logger name; a line logged after the capture returned → not in it (read through the `CapturedLogs` members `messagesAt` and `eventsAt`), and the logger keeps no appender; an action that throws → the same exception instance propagates and the logger keeps no appender; a capture on the package name receives a WARN from `<LogCaptureTest>.Child` and not one from `dev.notypie.other.LogCaptureProbe` |

## For AI Agents

### Working In This Directory
- Log at WARN or ERROR here: a Spring spec earlier in the same JVM may have set the root level to WARN, and lines
  below the effective level never reach the appender.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.logging.*'
```

## Dependencies

### Internal
- `infrastructure/src/testFixtures/kotlin/dev/notypie/logging/LogCapture.kt` — `captureLogs`, `CapturedLogs`

### External
Kotest, Logback classic (`Logger.iteratorForAppenders`), SLF4J `LoggerFactory`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
