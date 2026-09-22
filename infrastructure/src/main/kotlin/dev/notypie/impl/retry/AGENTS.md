<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-22 -->

# infrastructure/impl/retry

## Purpose
`RetryService`, the single retry helper the app uses for Slack Web API calls and other flaky I/O. It wraps
Spring Framework 7's core `RetryTemplate` and exposes per-call policy overrides with defaults taken from
`configurations/RetryOptions`.

## Key Files
| File | Description |
|------|-------------|
| `RetryService.kt` | `class RetryService()`. `fun <T> execute(action: () -> T, recoveryCallBack: (() -> T)? = null, maxAttempts = 3, initialDelay = 100, multiplier = 2.0, maxDelay = 10000, jitter = 10, exceptions = listOf(Exception::class.java)): T` looks up (or builds once) a `RetryTemplate` per distinct policy in a `ConcurrentHashMap<PolicyKey, RetryTemplate>` and runs on that; a `RetryException` invokes `recoveryCallBack` or is rethrown. `maxAttempts` is the total execution count (`maxRetries = maxAttempts - 1`) |

## For AI Agents

### Working In This Directory
- **One `RetryTemplate` per policy, never a shared mutable one.** Before 2026-09-22 every call assigned
  its policy to a singleton template, so a caller asking for `maxAttempts = 5` could run with another
  thread's `3`. `RetryServiceTest` races two policies to keep this from regressing.
- **`maxAttempts` is the total number of executions.** The service converts it to Spring's `maxRetries`
  (`maxAttempts - 1`), so the default `3` means three invocations, not four.
- **Only `RetryException` triggers recovery.** Exceptions outside `exceptions` are not retried and
  propagate unchanged; `recoveryCallBack` returning `null` for a nullable `T` falls back to rethrowing.
- **Retries only on exceptions.** `ApplicationMessageDispatcher.dispatch` returns a `CommandOutput` with
  `ok = false` for Slack-side rejections, so those are not retried here — by design.
- Callers: `impl/command/ApplicationMessageDispatcher`, `:application` `MeetingServiceImpl`,
  `SlackMessageRelayServiceImpl`. The bean comes from `configurations/RetryConfiguration.retryService`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.retry.RetryServiceTest'
```
The spec uses a bare `RetryTemplate()` and a counting action; it asserts success-after-failures with
`maxAttempts = maxFailures + 1` and the recovery path. Keep delays tiny (`initialDelay = 1`) so the suite
stays fast.

### Common Patterns
- Named arguments for every override; never positional.
- Defaults read from `RetryOptions.*.default` (an `internal` property, so only this module sees them).

## Dependencies

### Internal
- `configurations/RetryConfiguration.kt` — `RetryOptions`

### External
Spring Framework 7 core retry (`org.springframework.core.retry.RetryTemplate`, `RetryPolicy`,
`RetryException`), `org.springframework.util.backoff.FixedBackOff` (imported, unused).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
