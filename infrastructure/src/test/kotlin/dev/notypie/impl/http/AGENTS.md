<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-15 | Updated: 2026-10-26 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/http

## Purpose
Spec for the shared HTTP helpers in main `impl/http/BoundedHttp.kt`. Plain unit spec, no Spring, no server; the
`sendWithinDeadline` cases use a MockK `HttpClient`.

## Key Files
| File | Description |
|------|-------------|
| `BoundedHttpTest.kt` | `parseRetryAfter` edge values. Delta-seconds: `"7"` and `" 7 "` → 7 s, `"0"` → zero, `"86400"`, `"86401"` and a 23-digit number → `MAX_RETRY_AFTER` (24 h), `"-5"` and a 23-digit negative → zero (the dispatcher sleeps the value inline and `Thread.sleep` rejects a negative argument). RFC 1123 date: an hour ahead → more than 55 min and at most 1 h, an hour ago → zero, 2099 → 24 h. No hint (`null`): absent, `""`, `"   "`, `"abc"`, `"1e3"`, `"+5"`, `"7 seconds"`, an ISO-8601 instant. `sendWithinDeadline` over a body stream whose read throws (2026-10-26): the flag set and an `IOException(InterruptedException())` (the JDK's shape) → `InterruptedException` whose cause is that `IOException`, flag set; the same `IOException` with the flag clear → `InterruptedException`, flag set again; the flag set and a cause-less `IOException` → `InterruptedException`, flag set; a plain `IOException` on a thread that is not interrupted → the same instance, flag clear. Plain `BehaviorSpec` |

## For AI Agents

### Working In This Directory
- Dates are built from `Instant.now()` and asserted as ranges or at the clamps, because the parser reads the wall
  clock. Keep new date cases away from the boundaries.
- `sendWithinDeadline`'s deadline and size cap are covered by the `impl/cve` adapter specs (stalled body, oversized
  body); only its interrupt conversion is pinned here.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.http.*'
```

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/http/BoundedHttp.kt` — `parseRetryAfter`, `MAX_RETRY_AFTER`,
  `sendWithinDeadline`, `BoundedResponse`

### External
Kotest, MockK.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
