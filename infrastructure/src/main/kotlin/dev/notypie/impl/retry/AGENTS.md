<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/impl/retry

## Purpose
`RetryService`, the single retry helper the app uses for Slack Web API calls and other flaky I/O. It wraps
Spring Framework 7's core `RetryTemplate` and exposes per-call policy overrides with defaults taken from
`configurations/RetryOptions`.

## Key Files
| File | Description |
|------|-------------|
| `RetryService.kt` | `class RetryService(retryTemplate: RetryTemplate)`. `fun <T> execute(action: () -> T, recoveryCallBack: (() -> T)? = null, maxAttempts = 3, initialDelay = 100, multiplier = 2.0, maxDelay = 10000, jitter = 10, exceptions = listOf(Exception::class.java)): T` builds a `RetryPolicy` and assigns it to the template before running; a `RetryException` invokes `recoveryCallBack` or is rethrown. `private fun createFixedBackOffPolicy` is unused |

## For AI Agents

### Working In This Directory
- **The policy is assigned to the shared singleton on every call** (`retryTemplate.retryPolicy = policy`).
  Overrides such as `SlackMessageRelayServiceImpl`'s `maxAttempts = 5` are visible to any concurrent
  caller of the same template until the next call replaces them. If two callers need different policies
  under load, give them their own `RetryTemplate` instead of widening this class.
- **`maxAttempts` feeds `RetryPolicy.Builder.maxRetries`,** which counts retries *after* the first attempt
  — the default `3` allows up to four invocations. Name your override accordingly.
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
