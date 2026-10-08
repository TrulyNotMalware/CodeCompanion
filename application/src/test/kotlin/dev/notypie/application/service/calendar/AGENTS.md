<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# test/kotlin/dev/notypie/application/service/calendar

## Purpose
Specs for the Google Calendar connection lane in main `service/calendar/`: the slash actions, the OAuth
callback completion, the after-commit token revocation worker and the disabled-feature responder.

## Key Files
| File | Description |
|------|-------------|
| `CalendarConnectionServiceTest.kt` | `BehaviorSpec` with a `Harness` (MockK `GoogleCalendarConnectionRepository` / `GoogleOAuthStateRepository` / `GoogleOAuthClient`, capturing `OutboundMessageStager` and `ApplicationEventPublisher`, real `TokenCipher`, fixed `Clock`, `createStubTransactionManager()`). CONNECT: 43-char base64url state issued with the 10-minute TTL and no purge, DM link with `&amp;`-escaped URL and the state, DM `basicInfo` channel = user id, channel ephemeral says only "sent you a DM", "already connected" prefix. DISCONNECT: not connected (no delete, no event), connected (row and the user's states deleted — `delete` and `deleteForUser` once each — `GoogleTokenRevocationRequested` with the stored token, the row's `googleSubject` and `DISCONNECT_REASON`, `revoke` never called inline). STATUS: none / active with `<!date^` / revoked. `completeConnection`: missing state (no DB call), stale state (no purge, no exchange), denied consent (state consumed, purge in that transaction, no exchange), no code, exchange failure (nothing stored, nothing staged), calendar scope unticked by a first-time user (new token revoked inline, nothing stored, `SCOPE_DENIED`) and by an already connected user (no revoke), first connection (stored token is `v1.…`, never the clear token, decrypts back; DM names the account; no revocation), reconnection with the same subject (no revocation), with another subject or, subjects unknown, another e-mail (replaced grant published with `RECONNECT_REASON` and the old subject), with nothing known (no revocation), and two first-time callbacks racing (`saveActive` throws `DataIntegrityViolationException` once → called twice, one code exchange, `CONNECTED`, DM sent) |
| `GoogleTokenRevocationWorkerTest.kt` | Same-thread `Executor` and a MockK `GoogleCalendarConnectionRepository` (`find` returns the harness's `current` row): confirmed revoke → no DM; unconfirmed → `MANUAL_REMOVAL_HINT` DM; undecryptable token → no revoke, hint DM; `RejectedExecutionException` → hint DM; queued behind a reconnect — ACTIVE row with the same subject → skipped silently, subject unknown → skipped, another subject → still revoked; the event's `toString` prints user id, subject and reason but never the token |
| `GoogleTokenRevocationWorkerTransactionTest.kt` | Real H2 `DataSourceTransactionManager` (`createH2DataSource` / `createH2TransactionManager` from `service/meeting/MeetingTransactionFixtures`) in an `AnnotationConfigApplicationContext` that registers `TransactionalEventListenerFactory`, the transaction manager and the worker (recording `Executor`, MockK stager). A commit hands the task to the executor only after the transaction (size 0 inside, 1 after); a rollback hands off nothing; with a rejecting executor, the hint DM staged from the AFTER_COMMIT callback obtains a `DataSourceUtils` connection that is not the committed transaction's — the `REQUIRES_NEW` pin |
| `CalendarConnectionDisabledResponderTest.kt` | One ephemeral to the requester with `DISABLED_MESSAGE` and `CALENDAR_CONNECTION`, published once |

## For AI Agents

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.calendar.*'
```
Build the event with `createCalendarConnectionRequestEvent(action = …)` (domain testFixtures). `GoogleOAuthClient` is a
final class; MockK mocks it inline, so stub `authorizationUrl` / `exchangeCode` / `revoke` explicitly — no relaxed mocks,
because a relaxed `find` would return a mock instead of `null`. The bean wiring and the enabled/disabled pairing are
pinned by `configurations/CalendarConfigurationTest`, not here.
`GoogleTokenRevocationWorkerTransactionTest` must keep registering `TransactionalEventListenerFactory`: a bare
`AnnotationConfigApplicationContext` has no such factory, so `@TransactionalEventListener` degrades to a plain
`@EventListener` that fires inside the publishing transaction, and the commit / rollback assertions would then
fail — or pass — for the wrong reason.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
