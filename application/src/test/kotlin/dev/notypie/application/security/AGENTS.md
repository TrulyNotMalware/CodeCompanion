<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-22 -->

# test/kotlin/dev/notypie/application/security

## Purpose
Specs for the inbound Slack request boundary: HMAC signature verification with a replay window, the servlet
filter that applies it and short-circuits Slack retries, the body-caching request wrapper that keeps the raw
bytes readable after form parsing, and (in `mcp/`) the scoped turn-token codec.

## Key Files
| File | Description |
|------|-------------|
| `SlackSignatureVerifierTest.kt` | `SlackSignatureVerifier(clock).verify(signingSecret, requestTimestamp, requestSignature, body, toleranceSeconds)`: matching input → `valid`, `reason == null`; body changed after signing (`+` vs space) → `INVALID_SIGNATURE`; timestamp 301 s old with tolerance 300 → `EXPIRED_TIMESTAMP`; null timestamp → `MISSING_TIMESTAMP`; null signature → `MISSING_SIGNATURE`. The expected `v0=` value is minted with `createSignature`. |
| `SlackRequestVerificationFilterTest.kt` | `SlackRequestVerificationFilter.doFilter` with a real verifier, `InMemorySlackRetryDeduplicator(clock)`, and `AppConfig.Api(signingSecret, requestTimestampToleranceSeconds = 300)`. Valid signed form POST → chain invoked once with a `CachedBodyHttpServletRequest` whose `command` param is `/meetup`; same body re-sent with `X-Slack-Retry-Num: 1` → 200 and chain not invoked, also when the retry carries a fresh timestamp/signature; a first attempt whose chain sets 500 is retried through; `v0=invalid` → 401, chain not invoked. Private `signedRequest(rawBody, timestamp, signature, retryNum)` builder and `CountingFilterChain` live in the file. |
| `SlackRetryDeduplicatorTest.kt` | `SlackRequestFingerprint.of` hashes the body only; `isDuplicateRetry`: original (`retryNum = null`) then retry `"1"` → duplicate, `markFailed` then retry → allowed, retry with no prior original → allowed, entry older than the TTL → evicted, more entries than `maxEntries` → capped (`trackedEntries()`). |
| `CachedBodyHttpServletRequestTest.kt` | Wrapping a form-urlencoded `MockHttpServletRequest`: `inputStream` is re-readable (asserted twice), `hello+world` decodes to a space, repeated `payload` keys keep order, empty value → `""`, query-string params remain visible. |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `mcp/` | `ScopedTurnTokenCodec` mint/verify spec (see `mcp/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- The clock is pinned to the request timestamp (`Clock.fixed(Instant.ofEpochSecond(1714280000))`); changing
  the timestamp constant without moving the clock trips `EXPIRED_TIMESTAMP`.
- `MockHttpServletRequest` needs `contentType` and `characterEncoding` set before `setContent(...)` or
  parameter parsing yields nothing, and its stream is one-shot — build a fresh request per `doFilter`.
- Retry dedup fingerprints on method + URI + SHA-256(body). A retry that arrives first is allowed on
  purpose (the original may have been lost); keep that case when touching the deduplicator.
- These are plain Kotest specs that use `spring-test` mock servlet objects; no Spring context is started.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.security.*'
```
No `testFixtures` builders are used; secret, timestamp, and bodies are inline constants. Header names come from
`SlackHeaders` in main.

### Common Patterns
- Assert `response.status` and `filterChain.invocationCount` together — a 200 with a zero invocation count is
  the retry-acknowledged path, not a pass-through.
- `SlackSignatureVerificationFailureReason` enum values are the contract for log lines and metrics; new failure
  modes get a `then` here.

## Dependencies

### Internal
- `application/security/SlackSignatureVerifier.kt`, `SlackRequestVerificationFilter.kt`,
  `InMemorySlackRetryDeduplicator`, `SlackRequestFingerprint`, `CachedBodyHttpServletRequest.kt`, `SlackHeaders`
- `application/configurations/AppConfig.Api`

### External
`spring-test` (`MockHttpServletRequest`/`Response`), Jakarta Servlet, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
