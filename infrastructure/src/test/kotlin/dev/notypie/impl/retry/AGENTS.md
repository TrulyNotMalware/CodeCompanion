<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/retry

## Purpose
Spec for the retry wrapper in main `impl/retry/`. Uses a real Spring Framework `RetryTemplate`
(`org.springframework.core.retry`, not the separate `spring-retry` artifact) so the attempt counting is the
production algorithm, not a mock.

## Key Files
| File | Description |
|------|-------------|
| `RetryServiceTest.kt` | `RetryService.execute(action, recoveryCallBack?, maxAttempts?)`. Cases: a succeeding action returns its value; an always-failing action rethrows when no recovery is given; with `recoveryCallBack` the recovery value is returned after exhaustion; an action that fails `N` times then succeeds returns the success when `maxAttempts = N + 1`. Plain `BehaviorSpec`, no Spring context. |

## For AI Agents

### Working In This Directory
- The `N`-failures case uses a mutable counter closed over by the action lambda; keep new cases
  self-contained the same way rather than sharing counters across `` `when` `` blocks.
- Default `RetryTemplate()` settings are what the "exhausts and rethrows" case relies on. If `RetryService`
  starts configuring backoff or a different default attempt count, add a case that pins the new default.
- `RetryTemplate` moved into Spring core in Framework 7; do not reintroduce `org.springframework.retry`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.retry.*'
```
Fast and deterministic; no I/O.

### Common Patterns
`shouldThrow<Exception> { … }` for the exhaustion path; plain `shouldBe` for returned values.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/retry/RetryService.kt`

### External
`spring-core` `RetryTemplate`, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
