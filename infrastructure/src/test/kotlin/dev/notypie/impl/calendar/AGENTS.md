<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/calendar

## Purpose
Specs for `impl/calendar`: the Google OAuth client against a loopback HTTP server and the token cipher.

## Key Files
| File | Description |
|------|-------------|
| `GoogleOAuthClientTest.kt` | Loopback `HttpServer` with a swappable `respond`; captures path, `Content-Type` and the form body. Authorization URL flags/scopes/encoding (`%20`, no `+`); successful exchange (both tokens, `expires_in`, subject and e-mail from a hand-built `id_token`, the scope set, masked `toString`); no `id_token` with a partial `scope` → no subject/e-mail, calendar not granted; no `scope` field at all → the requested scopes; missing `refresh_token` → `GoogleOAuthException`; 400 `invalid_grant` → exception with code and status; non-JSON 504 → exception with status; revoke 200 → `true` with the token as a form field, 400 `invalid_token` → `true`, 400 `invalid_request` → `false`, 400 with a non-JSON body → `false`, 500 → `false` |
| `TokenCipherTest.kt` | Round trip, two encryptions differ, tampered ciphertext / foreign key / bad format rejected with `IllegalArgumentException`, 16-byte key rejected with the sizing message, non-base64 key rejected |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.calendar.*'
```
The HTTP stub pattern is the one `impl/cve/GithubReleaseSourceAdapterTest` uses; keep responses tiny so
`sendWithinDeadline`'s body watchdog never fires in the suite.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
