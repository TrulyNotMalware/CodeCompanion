<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-28 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/retry

## Purpose
Spec for the retry wrapper in main `impl/retry/`. Uses a real Spring Framework `RetryTemplate`
(`org.springframework.core.retry`, not the separate `spring-retry` artifact) so the attempt counting is the
production algorithm, not a mock.

## Key Files
| File | Description |
|------|-------------|
| `RetryServiceTest.kt` | `RetryService.execute(action, recoveryCallBack?, maxAttempts?, initialDelay?, jitter?, exceptions?)`. Cases: a succeeding action returns its value; an always-failing action without recovery throws `RetryException` whose `cause` is the very exception the action threw; an exception outside `exceptions` runs once and is wrapped the same way; with `recoveryCallBack` the recovery value is returned after exhaustion; an action that fails `N` times then succeeds returns the success when `maxAttempts = N + 1`; `maxAttempts` counts total executions; two concurrent callers with different `maxAttempts` each get their own policy. Plain `BehaviorSpec`, no Spring context. |

## For AI Agents

### Working In This Directory
- The `N`-failures case uses a mutable counter closed over by the action lambda; keep new cases
  self-contained the same way rather than sharing counters across `` `when` `` blocks.
- The `RetryException` + `cause` cases are the contract `ApplicationMessageDispatcher` depends on to find a
  wrapped `SlackRateLimitedException`; do not loosen them to `shouldThrow<Exception>`.
- `RetryTemplate` moved into Spring core in Framework 7; do not reintroduce `org.springframework.retry`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.retry.*'
```
Fast and deterministic; no I/O.

### Common Patterns
`shouldThrow<RetryException> { … }` plus `cause shouldBeSameInstanceAs original` for the failure paths;
plain `shouldBe` for returned values and attempt counts.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt`

### External
`spring-core` `RetryTemplate`, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
