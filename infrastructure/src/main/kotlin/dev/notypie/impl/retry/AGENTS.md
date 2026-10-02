<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# infrastructure/impl/retry

## Purpose
`RetryService`, the single retry helper the app uses for Slack Web API calls and other flaky I/O. It wraps
Spring Framework 7's core `RetryTemplate` and exposes per-call policy overrides with defaults taken from
`configurations/RetryOptions`.

## Key Files
| File | Description |
|------|-------------|
| `RetryService.kt` | `class RetryService()`. `fun <T> execute(action: () -> T, recoveryCallBack: (() -> T)? = null, maxAttempts = 3, initialDelay = 100, multiplier = 2.0, maxDelay = 10000, jitter = 10, exceptions = listOf(Exception::class.java)): T` looks up (or builds once) a `RetryTemplate` per distinct policy in a `ConcurrentHashMap<PolicyKey, RetryTemplate>` and runs on that; a `RetryException` invokes `recoveryCallBack` or is rethrown. `maxAttempts` is the total execution count (`maxRetries = maxAttempts - 1`). Top-level `retryTimeBound(attemptTimeout, maxAttempts = 3)`: the longest a run of the default policy takes when every attempt fails after `attemptTimeout` — the attempts plus each backoff at its jitter maximum (3 × 6 s → 18.32 s); the dispatcher's `SLACK_DISPATCH_TIME_BOUND` and the relay's shutdown budget are built on it |

## For AI Agents

### Working In This Directory
- **One `RetryTemplate` per policy, never a shared mutable one.** Before 2026-09-22 every call assigned
  its policy to a singleton template, so a caller asking for `maxAttempts = 5` could run with another
  thread's `3`. `RetryServiceTest` races two policies to keep this from regressing.
- **`maxAttempts` is the total number of executions.** The service converts it to Spring's `maxRetries`
  (`maxAttempts - 1`), so the default `3` means three invocations, not four.
- **Every failure surfaces as `RetryException` with the original exception as `cause`.** Spring 7 wraps
  both an exhausted retry and an exception outside `exceptions` (not retried, one attempt). So
  `recoveryCallBack` also runs for non-retryable exceptions, and `ApplicationMessageDispatcher` finds its
  `SlackRateLimitedException` by walking the cause chain and turns a `RetryException` whose `cause` is one of
  its transient types into the `transient_exhausted` outcome. `recoveryCallBack` returning `null` for a
  nullable `T` falls back to rethrowing. `RetryServiceTest` pins this contract.
- **Retries only on exceptions.** Callers decide what is retryable by throwing: the dispatcher throws for
  transient Slack errors and returns an `ok = false` `CommandOutput` for permanent ones.
- Callers: `impl/command/ApplicationMessageDispatcher` and `:application` `SlackMessageRelayServiceImpl` (terminal status
  write only). Do not call it inside a caller's transaction: the first failure marks the shared transaction
  rollback-only, so a retried "success" fails at commit. The bean comes from `configurations/RetryConfiguration.retryService`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.retry.RetryServiceTest'
```
The spec builds `RetryService()` directly with counting actions; it pins the `RetryException` + `cause`
contract, success-after-failures with `maxAttempts = maxFailures + 1`, the recovery path, `maxAttempts` as the
total execution count, and per-policy isolation under concurrency. Keep delays tiny (`initialDelay = 1`) so
the suite stays fast.

### Common Patterns
- Named arguments for every override; never positional.
- Defaults read from `RetryOptions.*.default` (an `internal` property, so only this module sees them).

## Dependencies

### Internal
- `configurations/RetryConfiguration.kt` — `RetryOptions`

### External
Spring Framework 7 core retry (`org.springframework.core.retry.RetryTemplate`, `RetryPolicy`,
`RetryException`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
