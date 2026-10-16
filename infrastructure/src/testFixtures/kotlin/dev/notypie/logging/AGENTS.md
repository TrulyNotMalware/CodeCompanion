<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-15 | Updated: 2026-10-15 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/logging

## Purpose
Shared Logback capture for the specs of both modules. It lives in `:infrastructure` testFixtures so that this
module's specs and `:application`'s (which consume `testFixtures(project(":infrastructure"))`) use the same helper.

## Key Files
| File | Description |
|------|-------------|
| `LogCapture.kt` | `captureLogs(loggerName, action)` attaches a started `ListAppender` to the named Logback logger, runs `action`, detaches and stops the appender in `finally` (an exception from `action` propagates unchanged) and returns `CapturedLogs(result, events)`: the action's value and a copy of the events logged during it. `eventsAt(level)` / `messagesAt(level)` filter by level; they exist as members of `CapturedLogs` and as extensions on `List<ILoggingEvent>`, so `val (value, logs) = captureLogs(...) { … }` reads `logs.messagesAt(level = Level.WARN)` the same way |

## For AI Agents

### Working In This Directory
- Pass the logger the code under test writes to: a class (`X::class.java.name`), a package
  (`X::class.java.packageName`, which receives every class in that package and below it through Logback additivity,
  as long as no logger in between turns additivity off),
  `Logger.ROOT_LOGGER_NAME`, or a literal package string. A wrong name captures nothing and every `shouldBeEmpty()` on
  that capture passes vacuously, so a spec that asserts "no WARN" also needs a case that asserts a positive line on
  the same logger.
- `KotlinLogging.logger {}` (8.0.4) cuts the lambda's class name at the first `Kt$` or `$`: a top-level logger is
  named after its file without `Kt`, and one declared inside a class (nested classes and companions included) after
  the outermost class. `X::class.java.name` matches only when the logger is declared in `X.kt` or inside a top-level `X`.
- Capture once and read each level from the result instead of nesting captures; return the action's value from the
  lambda and destructure it instead of collecting it in a side list.
- A capture whose action throws returns no events. To assert on both an exception and its log line, return
  `runCatching { … }.exceptionOrNull()` from the action and destructure it (`val (escaped, logs) = …`).
- Events below the logger's effective level never reach the appender. Once a Spring test context in the same JVM
  applies `logging.level.root: WARN` (`infrastructure/src/test/resources/application.yaml`), INFO and DEBUG lines are
  not captured on loggers that inherit the root level (no level set on them or on a package logger above them).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.logging.*'
```
Pinned by `src/test/kotlin/dev/notypie/logging/LogCaptureTest.kt`. Consumed by `GoogleOAuthClientTest`,
`GithubReleaseSourceAdapterTest`, `SlackUserEmailResolverTest`, `ApplicationMessageDispatcherTest` and, in
`:application`, `AgentConverseServiceTest`, `DebeziumLogTailingProcessorTest`,
`AgentAskThreadStarterTest`, `SlackMessageRelayServiceImplTest`, `MeetingServiceImplTest`,
`MeetingReminderSchedulingServiceTest`, `MeetingRescheduleServiceTest`, `CalendarConnectionServiceTest`,
`CalendarSyncServiceTest`, `CommandExecutorTest`, `SlackControllersTest` and `SlackRequestVerificationFilterTest`.

## Dependencies

### External
`ch.qos.logback:logback-classic` (`testFixturesApi`, version from the Spring Boot platform, because `ILoggingEvent` and
`Level` are in the helper's signatures) and the SLF4J `LoggerFactory` it brings.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
