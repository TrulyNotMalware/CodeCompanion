<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/impl/calendar

## Purpose
Google-side adapters for the per-user calendar connection: the OAuth 2.0 client (authorization URL, code
exchange, token revoke) over the JDK `HttpClient`, and the AES-GCM cipher that protects stored refresh
tokens. No Google client library — the three endpoints are plain form POSTs with JSON answers.

## Key Files
| File | Description |
|------|-------------|
| `GoogleOAuthClient.kt` | `(clientId, clientSecret, redirectUri, requestTimeout, authorizationEndpoint, tokenEndpoint, revokeEndpoint, maxBodyBytes = 64 KiB)`; the endpoint defaults are Google's, overridden only by tests. `authorizationUrl(state)`: `response_type=code`, `SCOPES` = `calendar.events openid email`, `access_type=offline`, `prompt=consent` (forces a refresh token on every consent), percent-encoded with `%20` for spaces. `exchangeCode(code): GoogleTokenGrant(accessToken, refreshToken, expiresInSeconds, scopes, subject, email)` (`toString` prints neither token; `grants(scope)` checks the space-separated `scope` field Google returns, which lists only what the user actually ticked under granular consent — `CALENDAR_EVENTS_SCOPE`; a response with no `scope` field at all is read as "the requested scopes" per RFC 6749 §5.1 and logged at WARN; `subject` is the id_token `sub`, the stable account id the connection service compares on reconnect) — `grant_type=authorization_code` form POST; non-2xx → `GoogleOAuthException(message with Google's `error`, statusCode)`, non-JSON → the same with the status, transport failure → the same without a status, interrupts rethrown; a 2xx without `access_token` or `refresh_token` also throws so no connection is ever stored without a long-lived credential. `subject` and `email` are read from the `id_token` payload without signature verification (display only: it came from Google's token endpoint over TLS; a malformed payload yields `null`). `revoke(token): Boolean` — `true` on 2xx and on a 400 whose JSON body carries `error=invalid_token` (`ALREADY_INVALID_TOKEN_ERROR`: the token is already gone, nothing left to revoke); any other 400 (`invalid_request`, a non-JSON body), any other status or a transport failure is `false` (logged, never thrown), which is what makes the worker send the manual-removal hint. Bodies are read through `sendWithinDeadline` (`impl/cve/SourceAdapter.kt`) so a stalled body cannot hang the caller |
| `TokenCipher.kt` | `TokenCipher(keyBase64)`: the key must decode (standard base64) to exactly 32 bytes, else `IllegalArgumentException` naming the size. `encrypt` → `v1.<iv>.<ciphertext>` (base64url, random 12-byte IV, 128-bit tag, AES/GCM/NoPadding); `decrypt` rejects an unknown version/shape, a wrong IV length, a tampered body or a foreign key with `IllegalArgumentException` (the `AEADBadTagException` is wrapped) |

## For AI Agents

### Working In This Directory
- **`prompt=consent` is deliberate.** Without it Google omits `refresh_token` on a repeat consent, and the
  connection would be useless after the first hour. Do not "optimise" it away.
- **Never log a token.** `exchangeCode` logs nothing on success; failures log Google's `error` code only.
- **Key rotation is not supported in place.** Changing `token-encryption-key` makes every stored token
  undecryptable; the connection service handles that as "revoke skipped, row deleted". A rotation would need
  a re-encrypt pass, not a second key.
- The revoke endpoint takes the refresh token; revoking it also kills the access tokens minted from it.
- **A 400 from `/revoke` is not a success by itself.** Google answers 400 `invalid_request` for a malformed
  call as well; only `invalid_token` means the token is already dead. Treating every 400 as "revoked" would
  swallow a client bug and leave the grant alive without telling the user.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.calendar.*'
```
`GoogleOAuthClientTest` runs a loopback `com.sun.net.httpserver.HttpServer` and asserts the form fields,
headers, URL encoding and every failure branch; `TokenCipherTest` covers round trip, IV randomness, tamper,
wrong key, bad format and bad key size.

## Dependencies

### Internal
- `infrastructure/common/JsonMapper.kt` — `jsonMapper`
- `infrastructure/impl/cve/SourceAdapter.kt` — `sendWithinDeadline`, `SourceResponse`
- Consumers: `application/configurations/CalendarConfiguration` (beans), `application/service/calendar/CalendarConnectionService`

### External
JDK `java.net.http`, `javax.crypto`, Jackson 3, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
