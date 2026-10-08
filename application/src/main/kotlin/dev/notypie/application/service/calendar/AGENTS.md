<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# application/service/calendar

## Purpose
Per-user Google Calendar connection for `/meetup calendar connect | disconnect | status`. The user links their
own Google account once (OAuth authorization code flow with offline access); the bot keeps the refresh token
encrypted so a later mirror worker can write meetings into that user's calendar. Nothing here talks to the
Calendar API yet — only to Google's OAuth endpoints. Beans are declared in
`configurations/CalendarConfiguration` (enabled) and `CalendarDisabledConfiguration` (disabled); no class here
carries a stereotype or `@Transactional`.

## Key Files
| File | Description |
|------|-------------|
| `CalendarConnectionService.kt` | Implements `CalendarConnectionCallback`. `@EventListener handle(CalendarConnectionRequestEvent)` runs the action inside a `TransactionTemplate` (joins the slash transaction when the event is delivered in-process): **CONNECT** issues a 32-byte `SecureRandom` base64url state bound to the requester with `state-ttl-minutes` (10) and DMs the requester a mrkdwn link `<authorizationUrl|Connect Google Calendar>` (the URL goes through `escapeMarkup`, so `&` becomes `&amp;`); the channel reply is only "I sent you a direct message…", prefixed with "already connected as … ; connecting again replaces it" when an active row exists. **DISCONNECT** deletes the row and that user's unconsumed states (`GoogleOAuthStateRepository.deleteForUser`, so a consent link issued before the disconnect cannot reconnect afterwards), publishes `GoogleTokenRevocationRequested` (reason `DISCONNECT_REASON`, carrying the row's `googleSubject`) through the Spring `ApplicationEventPublisher` and replies "disconnected… access at Google is being revoked"; no HTTP call happens on the Slack thread. **STATUS** renders not-connected / connected-as-… since `<!date^…>` (Slack renders the viewer's local time) / revoked-on-…. `completeConnection(code, state, error): CalendarConnectionOutcome` is the callback path: one transaction consumes the state (one atomic UPDATE; `INVALID_STATE` when missing, unknown, expired or already used) and, only after a successful consume, purges expired states; `DENIED` when Google sent `error`; `INVALID_STATE` when no code; then `exchangeCode` outside any transaction (`EXCHANGE_FAILED` on `GoogleOAuthException`); `SCOPE_DENIED` when the grant lacks `CALENDAR_EVENTS_SCOPE` — nothing is stored, and the new refresh token is revoked at once (browser thread, no transaction) only when the user has no ACTIVE connection, for the same grant-wide reason; else a second transaction (`storeConnection`) runs `saveActive` with the AES-GCM-encrypted token plus the id_token `sub`/`email` — retried once as a whole when it throws `DataIntegrityViolationException`, because two first-time callbacks racing on the unique `slack_user_id` key make the loser's insert collide and the retry finds the row and rewrites it — publishes a `GoogleTokenRevocationRequested` (reason `RECONNECT_REASON`, with the replaced row's `googleSubject`) for a replaced grant **only when the account is known to differ** (`isDifferentAccountFrom`: subjects compared when both known, else e-mails case-insensitively, else no revoke — Google's revoke endpoint revokes the whole grant for that account and client, so a same-account revoke would also kill the token just stored; the old same-account token simply ages out of Google's per-client cap), and DMs "Google Calendar connected as <email>" |
| `GoogleTokenRevocationWorker.kt` | `data class GoogleTokenRevocationRequested(userId, googleSubject, encryptedRefreshToken, reason)` (`toString` hides the token) and the worker: `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` hands the event to the `googleTokenRevocationExecutor`; the task (`revoke`, `internal`) first re-reads the user's connection and **skips the revoke when an ACTIVE row exists whose account is not known to differ** (same `googleSubject`, or a subject unknown on either side) — the user reconnected while the revoke was queued and Google's revoke is grant-wide, so the HTTP call would kill the token now in use; otherwise it decrypts, calls `GoogleOAuthClient.revoke` and, when Google does not confirm, when the token cannot be decrypted or when the executor rejects the task, DMs `MANUAL_REMOVAL_HINT` (myaccount.google.com/permissions) in a `REQUIRES_NEW` `TransactionTemplate` — the rejection branch runs inside the AFTER_COMMIT callback, where the committed transaction's resources are still bound and a REQUIRED template would join a finished transaction (`GoogleTokenRevocationWorkerTransactionTest` pins the separate connection). A confirmed revoke is only logged |
| `CalendarConnectionCallback.kt` | `enum CalendarConnectionOutcome { CONNECTED, DENIED, SCOPE_DENIED, INVALID_STATE, EXCHANGE_FAILED }` and the interface the callback controller depends on |
| `CalendarConnectionDisabledResponder.kt` | The disabled-mode listener: answers every `CalendarConnectionRequestEvent` with the ephemeral `DISABLED_MESSAGE` inside a `TransactionTemplate`, so the slash command never goes silent |
| `CalendarReplies.kt` | `internal` helpers: `stageCalendarEphemeral` (requester-only, headline `CodeCompanion — Google Calendar`, `CALENDAR_CONNECTION`) and `stageCalendarDirectMessage` (`ChannelMessage` to the user id with `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)`, the reminder-DM shape); both `checkNotNull` the staged event and `publishOne` |

## For AI Agents

### Working In This Directory
- **Exactly one of the two listeners is a bean.** `CalendarConfiguration` and `CalendarDisabledConfiguration`
  carry `OnGoogleCalendarEnabled` / `OnGoogleCalendarDisabled`, both derived from the bound
  `calendar.google.enabled` Boolean (`CalendarConfigurationTest` pins `true`/`yes`/`on`/`1`/`false`/`no`/absent).
  Two replies or none are both bugs.
- **No Google HTTP call on the Slack thread.** The slash command is acknowledged within Slack's 3-second budget,
  and the listener joins the slash transaction, so the revoke goes through `GoogleTokenRevocationRequested`
  after commit onto the revocation executor. The only inline Google calls are on the callback (browser) thread:
  `exchangeCode` and the `SCOPE_DENIED` revoke, both outside a transaction.
- **The consent link is a DM, not an ephemeral.** The state is single-use and user-bound, but the DM also
  survives channels the bot is not a member of (`chat.postEphemeral` fails there with `not_in_channel`).
- **The expired-state purge lives in the callback's consume transaction, not in `connect`.** A range `DELETE`
  next to the `INSERT` of a new state in the same transaction gap-locks the `expires_at` index under
  REPEATABLE READ and two concurrent connects can deadlock; the consume transaction inserts nothing.
- **Tokens never reach a message or a log.** Logs carry the Slack user id, Google's error code and the granted
  scope list; the e-mail goes only into the user's own DM and `status`. The refresh token is encrypted before
  `saveActive` and decrypted only by the revocation worker.
- **Accepted:** a user who forwards their own consent DM lets someone else attach *their* Google account to
  the forwarder's Slack user (the state binds the Slack user, not the browser). The DM is private to the
  requester and the connected e-mail is shown in the confirmation DM and in `status`.
- A revoke lost at shutdown or by a crash is best-effort by design: the executor waits 0 s and drops queued revokes
  (logged), so the Pod's termination budget (`ShutdownBudgetTest`, `k8s/deployment.yaml`) is unchanged; the row is
  already gone, and the user can remove the grant at Google by hand.
- **Never revoke a token that may belong to the account still in use.** The same-account cases above are the
  reason `saveActive` returns the replaced row, `SCOPE_DENIED` checks for an ACTIVE connection first, every
  `GoogleTokenRevocationRequested` carries the `googleSubject` of the grant it names, and the worker re-reads the
  connection right before the HTTP call. Two races remain and are accepted: a disconnect while a callback's code
  exchange is in flight (the callback then stores a token the user just asked to drop), and a same-account
  reconnect while the revoke HTTP call is already running (the new token dies with the grant). Both surface as
  `invalid_grant` on the first refresh; slice 2 turns that into `REVOKED` plus a "reconnect" DM.
- **Disconnect also clears the user's unconsumed states.** A consent link issued before `disconnect` and
  clicked after it would otherwise reconnect the account without a fresh `connect`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.calendar.*'
./gradlew :application:test --tests 'dev.notypie.application.configurations.CalendarConfigurationTest'
```
`CalendarConnectionServiceTest` builds the service with MockK repositories, `GoogleOAuthClient` and
`ApplicationEventPublisher`, a real `TokenCipher` (32-byte test key), a fixed `Clock`,
`createStubTransactionManager()` and an `AppConfig` whose `calendar.google` is enabled; it asserts the staged
DM / ephemeral shapes, the issued state shape and TTL, the per-user state delete on disconnect, the published
revocation events (same account → none, other account by subject or e-mail → one, unknown → none; each carries the
replaced subject), the one-shot `saveActive` retry on a unique-key collision, the decrypted stored token and every
`completeConnection` outcome. `GoogleTokenRevocationWorkerTest` drives the worker with a same-thread `Executor`
(confirmed / unconfirmed / undecryptable / rejected, and the reconnect skip by subject).
`GoogleTokenRevocationWorkerTransactionTest` publishes the event through a small `AnnotationConfigApplicationContext`
over a real H2 `DataSourceTransactionManager` (`service/meeting/MeetingTransactionFixtures`): the executor receives
the task only after commit, a rollback hands off nothing, and the rejection-path DM runs on a connection other than
the committed transaction's. Events come from `createCalendarConnectionRequestEvent` (domain testFixtures).

## Dependencies

### Internal
- `domain/command/entity/event/` — `CalendarConnectionRequestEvent`, `CalendarConnectionPayload`, `CalendarConnectionAction`
- `infrastructure/impl/calendar/` — `GoogleOAuthClient` (`CALENDAR_EVENTS_SCOPE`), `GoogleOAuthException`, `GoogleTokenGrant`, `TokenCipher`
- `infrastructure/repository/calendar/` — `GoogleCalendarConnectionRepository` (`ConnectionSaved`), `GoogleOAuthStateRepository`, `schema/CalendarConnection`
- `application/configurations/` — `AppConfig.Calendar.Google`, `CalendarConfiguration`, `CalendarDisabledConfiguration`, `conditions/OnGoogleCalendarEnabled`
- Consumer: `application/controllers/GoogleOAuthCallbackController` (through `CalendarConnectionCallback`)

### External
Spring context events and transactions, kotlin-logging, `java.security.SecureRandom`, a `java.util.concurrent.Executor`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
