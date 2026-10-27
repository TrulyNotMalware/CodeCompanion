<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-27 -->

# test/kotlin/dev/notypie/application/security

## Purpose
Specs for the inbound Slack request boundary: HMAC signature verification with a replay window, the servlet
filter that applies it and short-circuits Slack retries, the body-caching request wrapper that keeps the raw
bytes readable after form parsing, and (in `mcp/`) the scoped turn-token codec and the MCP turn-token filter.

## Key Files
| File | Description |
|------|-------------|
| `SlackSignatureVerifierTest.kt` | `SlackSignatureVerifier(clock).verify(signingSecret, requestTimestamp, requestSignature, body, toleranceSeconds)`: matching input → `valid`, `reason == null`; body changed after signing (`+` vs space) → `INVALID_SIGNATURE`; timestamp 301 s old with tolerance 300 → `EXPIRED_TIMESTAMP`; null timestamp → `MISSING_TIMESTAMP`; null signature → `MISSING_SIGNATURE`. `checkHeaders`: upper-cased valid signature → `MALFORMED_SIGNATURE`; valid headers → `valid`; `Long.MIN_VALUE + now` (the subtraction overflows), `Long.MIN_VALUE` and `Long.MAX_VALUE` → `EXPIRED_TIMESTAMP`; exactly `now ± tolerance` → still valid; `now + 301` → `EXPIRED_TIMESTAMP` (fails when the future edge is widened by a second); timestamp `"soon"` → `INVALID_TIMESTAMP`. Two signatures computed outside the JVM (2026-10-27): `REFERENCE_FORM_SIGNATURE` over `REFERENCE_FORM_BODY` and `REFERENCE_UTF8_SIGNATURE` over `REFERENCE_UTF8_BODY` (a JSON body with 2-, 3- and 4-byte UTF-8 characters: é, ☕, 🚀, ï) → `valid`; they fail when the `v0:` prefix, the secret or the timestamp leaves the HMAC input, and the UTF-8 one also when the body is re-encoded (the ASCII form vector stays green there). The same body and a `createSignature` value under `"other-secret"`, or under a timestamp 60 s earlier (inside the window) → `INVALID_SIGNATURE` (each fails when that input is dropped from the HMAC). The other cases mint their `v0=` value with `createSignature`. |
| `SlackRequestVerificationFilterTest.kt` | `SlackRequestVerificationFilter` built by a local `filter(signingSecret, profiles, meterRegistry, retryDeduplicator)` with a real verifier, `InMemorySlackRetryDeduplicator(clock)`, a `MockEnvironment` and a `SimpleMeterRegistry`. Valid signed slash form POST → chain invoked once with a `CachedBodyHttpServletRequest` whose `command` param is `/meetup`; an events request carrying `REFERENCE_UTF8_BODY` and the externally computed `REFERENCE_UTF8_SIGNATURE` → 200, chain invoked once (2026-10-27; `signed()` mints with `createSignature`, so this is the only filter case whose signature does not come from the verifier); `v0=x` → 401; a `/api/slash/calendar` form with `v0=x` → 401 before the chain (the `/api/slash` prefix covers the new endpoint); declared Content-Length over the limit (with well-formed headers) → 413; a signed form body replayed with `?payload=forged&user_id=U_ADMIN&extra=1` (also added as container parameters) → handlers see only body values, no `extra`, `queryString == null`. A rejected request's log line carries the reason text, not a lambda object (`captureLogs` on the root logger; guards the inherited `GenericFilterBean.logger` shadowing a file-level `logger`). Missing headers, non-numeric or stale timestamp, signature without `v0=`, not 64 hex digits, or upper-case hex → 401 and the body is never read (`BodyReadTrackingRequest`). Events path: retry after a completed original (fresh timestamp/signature) → 200, chain not invoked; the same signed request replayed without a retry number → 200, chain invoked once in total, and its INFO line reads `retryNum=none`; retry while the original is still running (fired from inside the original's chain) → 503, chain not invoked, `METRIC_DEFERRED_RETRIES` counter = 1; the same body replayed without a retry number while the original is still running → 503, chain not invoked, and its INFO line reads `retryNum=none`; a different event arriving while a `maxEntries = 1` deduplicator is full of in-flight entries → still reaches the chain (untracked); original answering 500 or throwing `StackOverflowError` → retry reaches the chain; slash body repeated with a retry number → both reach the chain. Unsigned requests to encoded/normalised variants (`%63`, `..`, `.`, `;jsessionid`, trailing slash, mixed case, `//`, `%2F`, `%73h`, `%69nteraction`) and to a raw URI whose container `servletPath` is the events path → 401; `/actuator/health` passes. Secret policy: real value → created; blank under `dev`, no profile, or `local` combined with `slack-live` / `prod` / `dev` → `IllegalStateException` whose message names every active profile; a real secret with `local` combined with `slack-live` / `prod` / `dev` → `IllegalStateException` saying `local` "cannot be combined with other profiles" and naming every active profile; a real secret under `local` alone → created; literal `${SLACK_SIGNING_SECRET}` even under `local` → `IllegalStateException`; blank under `local` → verification skipped; blank under `local` with an `Origin` of `https://attacker.example`, `http://localhost` (same-origin, what DNS rebinding produces; fails a switch to `CorsUtils.isCorsRequest`) or the literal `null`, on the events path (JSON body), `/api/slash/ask` (form body) and `/api/slack/interaction` (form `payload`) → 401, chain not invoked; a real secret and a correctly signed `/api/slash/ask` form that also carries `Origin` → 200, chain invoked once (the signed path ignores `Origin`); an unsigned request to `/api/slack/x%C2%85a%E2%80%A8b%E2%80%AEc` → one `Rejected Slack request headers` WARN containing `path=/api/slack/x?a?b?c` (2026-10-26; fails when the path is logged raw); the same URI with an `Origin` under a blank secret and `local` → one `Rejected browser-originated request` WARN containing `path=/api/slack/x?a?b?c` (2026-10-27; fails when that branch's `loggedPath` skips `neutralizeControlCharacters`); a rejected `Origin` of `https://x\n\u0085\u009b31m\u2028\u202ey` → exactly one `Rejected browser-originated request` WARN (`captureLogs` on the filter's logger) containing `origin=https://x???31m??y` (fails on the old `\p{Cntrl}` replacement, which leaves NEL, CSI, U+2028 and U+202E in place); `application-local.yaml` loaded through `YamlPropertySourceLoader` → `server.address` is `127.0.0.1`. Private `slackRequest` / `unsignedRequest` builders, `CountingFilterChain(statusToSet, onInvoke)` and `BodyReadTrackingRequest` live in the file. |
| `SlackRetryDeduplicatorTest.kt` | `SlackRequestFingerprint.of` hashes the body only; `admit` (file-private `firstAttempt(...)` asserts `FirstAttempt` and returns its ticket): retry while in flight → `RetryOfInFlight`, after `markCompleted(ticket)` → `RetryOfCompleted`, after `markFailed(ticket)` → `FirstAttempt`, retry with no prior original → tracked `FirstAttempt`, identical body without retry number after the original completed → `RetryOfCompleted`, and while it runs → `RetryOfInFlight`, completed entry past the TTL (`MutableClock`) → `FirstAttempt`; two racing in-flight requests → exactly one `FirstAttempt`; cap 10 with 10 completed → trimmed to 90% and the 11th is tracked (10 entries); cap 3 with 5 in-flight requests → the last two `Untracked`, map stays at 3, none evicted; `maxEntries = 1` with 5 in flight → 1 entry; a stale ticket from an expired attempt → its `markFailed` / `markCompleted` leave the newer attempt IN_FLIGHT, the current ticket completes it; `maxEntries = 0` → `IllegalArgumentException`. |
| `CachedBodyHttpServletRequestTest.kt` | `cacheWithinLimit` on a form-urlencoded `MockHttpServletRequest`: `inputStream` is re-readable (asserted twice), `hello+world` decodes to a space, repeated `payload` keys keep order, empty value → `""`, and the query string is ignored by every parameter accessor while `queryString` is `null`; a body exactly at `MAX_BODY_BYTES` is cached, one byte more is refused, and a chunked body (`contentLengthLong = -1`) over the limit is refused by the bounded read. |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `mcp/` | `ScopedTurnTokenCodec` mint/verify and `McpTurnTokenFilter` address and token gate specs (see `mcp/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- The clock is pinned to the request timestamp (`Clock.fixed(Instant.ofEpochSecond(1714280000))`); changing
  the timestamp constant without moving the clock trips `EXPIRED_TIMESTAMP`.
- The `REFERENCE_*_SIGNATURE` values must never be produced by `createSignature`. They were computed outside the JVM
  and cross-checked with a second tool; recompute both when the secret, timestamp or a body changes:
  `python3 -c 'import hmac,hashlib; print("v0="+hmac.new(b"secret", b"v0:1714280000:"+BODY.encode("utf-8"),
  hashlib.sha256).hexdigest())'` and `printf 'v0:1714280000:%s' "$BODY" | openssl dgst -sha256 -hmac secret`.
  Kotlin writes the UTF-8 body with `\u` escapes (a surrogate pair for 🚀) so both sides hash the same code points.
- `MockHttpServletRequest` needs `contentType` and `characterEncoding` set before `setContent(...)` or
  parameter parsing yields nothing, and its stream is one-shot — build a fresh request per `doFilter`.
- Retry dedup fingerprints on method + normalised path + SHA-256(body) and only on `/api/slack/events`, so
  retry scenarios must use that path (JSON body). A retry that arrives first is processed on purpose (the
  original may have been lost); keep that case when touching the deduplicator.
- The filter constructor validates the signing secret, so every filter needs an `Environment`; use
  `MockEnvironment().apply { setActiveProfiles(...) }`.
- These are plain Kotest specs that use `spring-test` mock servlet objects; no Spring context is started.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.security.*'
```
The only testFixtures helper used is `infrastructure`'s `logging/LogCapture.kt` (`captureLogs`); secret, timestamp,
and bodies are inline constants. The `REFERENCE_*` constants are `internal` top-level values in
`SlackSignatureVerifierTest.kt`, shared with the filter spec. Header names come from
`SlackHeaders` in main.

### Common Patterns
- Assert `response.status` and `filterChain.invocationCount` together — a 200 (or 503) with a zero invocation
  count is the retry-acknowledged (or deferred) path, not a pass-through.
- `SlackSignatureVerificationFailureReason` enum values are the contract for log lines and metrics; new failure
  modes get a `then` here.

## Dependencies

### Internal
- `application/security/SlackSignatureVerifier.kt`, `SlackRequestVerificationFilter.kt`,
  `InMemorySlackRetryDeduplicator`, `SlackRequestFingerprint`, `CachedBodyHttpServletRequest.kt`, `SlackHeaders`
- `application/configurations/AppConfig.Api`, `spring-test` `MockEnvironment`

### External
`spring-test` (`MockHttpServletRequest`/`Response`), Jakarta Servlet, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
