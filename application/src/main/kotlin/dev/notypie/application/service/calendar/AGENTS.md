<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-11 -->

# application/service/calendar

## Purpose
Per-user Google Calendar connection for `/calendar connect | disconnect | status`, and the mirror that
copies meetings into each connected user's `primary` calendar. The user links their own Google account once
(OAuth authorization code flow with offline access); the bot keeps the refresh token encrypted. The meeting
listeners mark `(meeting, user)` pairs dirty in `meeting_calendar_event` through `MeetingCalendarMirror`, and a
60-second scheduler re-derives what each marked user's calendar should hold and calls the Calendar API. Beans are
declared in `configurations/CalendarConfiguration` (enabled) and `CalendarDisabledConfiguration` (disabled); only
`CalendarSyncScheduler` and the `/calendar` entry point `CalendarSlashServiceImpl` are component-scanned, and only
that entry point carries `@Transactional` (the slash transaction, like every other `*SlashService`).

## Key Files
| File | Description |
|------|-------------|
| `CalendarSlashService.kt` | `interface CalendarSlashService { handleCalendar(headers, payload, commandData) }` and `@Service CalendarSlashServiceImpl(commandExecutor)`: `@Transactional`, builds `domain` `CalendarCommand` with `IdempotencyCreator.create(commandData)` and runs it through `CommandExecutor`, whose intent becomes the `CalendarConnectionRequestEvent` the listener below (or the disabled responder) answers inside that transaction. Called by `SlashCommandController` (`POST /api/slash/calendar`) and, under the `local` profile, by `SocketModeReceiver` for `slack.app.socket.calendar-command` (default `/calendar`). The command moved out of `/meetup` on 2026-10-08: linking a calendar is not meeting-specific, and other lanes (standups) may mirror into it later |
| `CalendarConnectionService.kt` | Implements `CalendarConnectionCallback`. `@EventListener handle(CalendarConnectionRequestEvent)` runs the action inside a `TransactionTemplate` (joins the slash transaction when the event is delivered in-process): **CONNECT** issues a 32-byte `SecureRandom` base64url state bound to the requester with `state-ttl-minutes` (10) and DMs the requester a mrkdwn link `<authorizationUrl|Connect Google Calendar>` (the URL goes through `escapeMarkup`, so `&` becomes `&amp;`); the channel reply is only "I sent you a direct message…", prefixed with "already connected as … ; connecting again replaces it" when an active row exists. **DISCONNECT** deletes all of that user's consent states (`GoogleOAuthStateRepository.deleteForUser`) whether or not a connection exists, so a consent link issued before the disconnect cannot connect afterwards, also for a user who ran `connect` and then `disconnect` before opening the link; with no connection row it then only replies "not connected", and otherwise it deletes the row, fails that user's `PENDING` mirror queue rows (`failPendingForUser(userId, DISCONNECTED_ERROR = "disconnected", now)`; nothing is deleted: `SYNCED` and `FAILED` rows stay for a later connect to revive, and the events already mirrored stay in their calendar, since there is no token left to delete them with), publishes `GoogleTokenRevocationRequested` (reason `DISCONNECT_REASON`, carrying the row's `googleSubject`) through the Spring `ApplicationEventPublisher` and replies "disconnected… access at Google is being revoked"; no HTTP call happens on the Slack thread. **STATUS** renders not-connected / connected-as-… since `<!date^…>` (Slack renders the viewer's local time) / revoked-on-…. `completeConnection(code, state, error): CalendarConnectionOutcome` is the callback path: one transaction consumes the state (one atomic UPDATE; `INVALID_STATE` when missing, unknown, expired or already used) and, only after a successful consume, purges expired states; `DENIED` when Google sent `error`; `INVALID_STATE` when no code; then `exchangeCode` outside any transaction (`EXCHANGE_FAILED` on `GoogleOAuthException`); `SCOPE_DENIED` when the grant lacks `CALENDAR_EVENTS_SCOPE` — nothing is stored, and the new refresh token is revoked at once (browser thread, no transaction) only when the user has no ACTIVE connection, for the same grant-wide reason; else a second transaction (`storeConnection`) runs `saveActive` with the AES-GCM-encrypted token plus the id_token `sub`/`email` — retried once as a whole when it throws `DataIntegrityViolationException` (two first-time callbacks racing on the unique `slack_user_id` key make the loser's insert collide, and the retry finds the row and rewrites it) or `ConcurrencyFailureException` (a MariaDB 1020 snapshot conflict arrives as `SnapshotIsolationConflictException`); any other exception escapes, and when the retry hits one of those two again the outcome is `STORE_FAILED` (ERROR with the user id; the state is already consumed, so the page asks for a new `connect`). Everything the store writes, the confirmation DM's outbox row included, rolls back with its transaction, and the state consume and the code exchange sit outside it, so a retry repeats no Google call. Right after `saveActive`, in that transaction, it publishes a `GoogleTokenRevocationRequested` (reason `RECONNECT_REASON`, with the replaced row's `googleSubject`; the worker receives it after commit, so a rolled-back attempt revokes nothing) for a replaced grant **only when the account is known to differ** (`isDifferentAccountFrom`: subjects compared when both known, else e-mails case-insensitively, else no revoke — Google's revoke endpoint revokes the whole grant for that account and client, so a same-account revoke would also kill the token just stored; the old same-account token simply ages out of Google's per-client cap). Then comes the **connect back-fill**: `getMeetingsByUserIdInRange(userId, now - BACKFILL_LOOKBACK_DAYS (1), now + BACKFILL_HORIZON_YEARS (2))` in the clock's zone (the range is on `startAt`; meetings the user publishes or participates in, canceled ones included); a meeting is kept only when the user is the creator, the meeting is not canceled and its end (`endAt` when it is after the start, else start + 1 h, the rule `loadSyncView` uses) is after now, so a meeting in progress is mirrored; each kept meeting is `enqueue(meetingId, userId, now)`, and then one `touchForUser(userId, meetingIds, now)` with the ids of **every** meeting the read returned (hosted, attended and canceled ones alike, not only the queued ones; an empty list when it returned none) revives the user's existing queue rows of those meetings, `FAILED` ones included, so the worker also deletes the event of a meeting canceled while the connection was down. Rows of meetings outside the read stay as they are, so a reconnect does not re-sync the user's history. Last, it DMs "Google Calendar connected as <email>" |
| `GoogleTokenRevocationWorker.kt` | `data class GoogleTokenRevocationRequested(userId, googleSubject, encryptedRefreshToken, reason)` (`toString` hides the token) and the worker: `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` hands the event to the `googleTokenRevocationExecutor`; the task (`revoke`, `internal`) first re-reads the user's connection and **skips the revoke when an ACTIVE row exists whose account is not known to differ** (same `googleSubject`, or a subject unknown on either side) — the user reconnected while the revoke was queued and Google's revoke is grant-wide, so the HTTP call would kill the token now in use; otherwise it decrypts, calls `GoogleOAuthClient.revoke` and, when Google does not confirm, when the token cannot be decrypted or when the executor rejects the task, DMs `MANUAL_REMOVAL_HINT` (myaccount.google.com/permissions) in a `REQUIRES_NEW` `TransactionTemplate` — the rejection branch runs inside the AFTER_COMMIT callback, where the committed transaction's resources are still bound and a REQUIRED template would join a finished transaction (`GoogleTokenRevocationWorkerTransactionTest` pins the separate connection). A confirmed revoke is only logged. The task body runs inside `containFailure` (`service/standup`): any other `Exception` it throws (for example the connection re-read during a database outage) is logged at ERROR with the user id and reason and answered with the same hint DM. That DM is best-effort: its own transaction can fail for the same reason, and that failure is only logged. An `InterruptedException` restores the interrupt flag and is rethrown, and an `Error` is not caught |
| `CalendarConnectionCallback.kt` | `enum CalendarConnectionOutcome { CONNECTED, DENIED, SCOPE_DENIED, INVALID_STATE, EXCHANGE_FAILED, STORE_FAILED }` and the interface the callback controller depends on |
| `CalendarConnectionDisabledResponder.kt` | The disabled-mode listener: answers every `CalendarConnectionRequestEvent` with the ephemeral `DISABLED_MESSAGE` inside a `TransactionTemplate`, so the slash command never goes silent |
| `MeetingCalendarMirror.kt` | The hook port the meeting lane calls inside its write transactions: `onMeetingCreated(meetingIdempotencyKey, hostId)`, `onAttendanceChanged(meetingIdempotencyKey, userId, attending)`, `onMeetingCanceled(meetingUid)`, `onMeetingRescheduled(meetingId)`. `NoopMeetingCalendarMirror` (an `object`) is the disabled-mode bean, so `MeetingServiceImpl` / `MeetingRescheduleService` always resolve one. `MeetingCalendarMirrorService(queue, connections, meetings, clock)` runs in the caller's transaction (the repository methods are `@Transactional` REQUIRED and join); every method first checks `TransactionSynchronizationManager.isActualTransactionActive()` and, outside a transaction, throws `IllegalStateException("MeetingCalendarMirror.<hook> must join the meeting write's transaction")` before any repository call. Created → only when `hasActiveConnection(host)`, `MeetingRepository.findMeetingId(key)` (`null` → WARN, nothing) then `enqueue(meetingId, host, now)`; attendance `true` → the same connection check, id lookup and `enqueue`; attendance `false` → id lookup and `touchOne` (never an insert, no connection check: a user who was never mirrored has no row and the touch matches nothing); canceled → `touchMeetingByUid`; rescheduled → `touchMeeting`. The connection check runs before the id lookup, so the common case (an unconnected user) costs one indexed `SELECT` |
| `GoogleAccessTokenProvider.kt` | `enum class RevocationCause(lastError) { GRANT_REJECTED("refresh rejected: invalid_grant"), TOKEN_UNREADABLE("stored token unreadable") }` and `sealed interface AccessTokenResult { Granted(accessToken) (toString hides it), NotConnected, Revoked(observedEncryptedRefreshToken, cause) (toString shows only the cause), Misconfigured(message), Unavailable(message) }`. The provider only observes; it writes nothing. `accessToken(userId)`: `connections.find` first, on every call (`null` or not `ACTIVE` → `NotConnected`, cache entry dropped); a `ConcurrentHashMap` entry is reused only while it was minted from the stored `encryptedRefreshToken` and `now < expiresAt - 60 s` (`REFRESH_MARGIN`); otherwise `TokenCipher.decrypt` and `GoogleOAuthClient.refresh`. An undecryptable token (`IllegalArgumentException`, for example after a key rotation) → `Revoked(the ciphertext read, TOKEN_UNREADABLE)`; `GoogleOAuthException` with `invalid_grant` → `Revoked(the ciphertext read, GRANT_REJECTED)`; both evict and log WARN with the cause's `lastError`. `invalid_grant` means revoked **or expired** (per Google's OAuth documentation, not yet observed here: a refresh token of an app in the Testing publishing status lives 7 days, and Google drops tokens past the per-account client cap or after 6 months unused). `invalid_client` / `unauthorized_client` → `Misconfigured("Google rejected the OAuth client: <code>")`; any other `GoogleOAuthException` → `Unavailable(message)`. After a successful refresh the row is read again: gone, not `ACTIVE`, or holding another ciphertext → `Unavailable(CHANGED_DURING_REFRESH = "connection changed during refresh")`, nothing cached (INFO); otherwise the token is cached for `expires_in` with the ciphertext it came from. `evict(userId)` drops the cached token. Logs carry the user id, Google's status and error code, never a token. The companion object is last |
| `CalendarSyncService.kt` | `syncDue()`, driven by `CalendarSyncScheduler`. Per tick: `resetStuck(olderThan = now - sync-stuck-minutes)` (WARN when > 0), `findDue(now, sync-batch-size)`, then row by row until the next row would start at or after `sync-tick-budget-seconds` since the tick began (INFO with the rows left). Each row runs in `containFailure`, so one bad row cannot end the tick, and interrupts are rethrown. An exception before the claim logs ERROR `rowId=…`; one after it logs ERROR `rowId=… meetingId=… userId=…` and then runs the ordinary backoff with the exception's message (or class name) as `last_error` (and in the backoff WARNs), while the failure DM at `sync-max-attempts` shows the generic reason "an internal error" (`INTERNAL_ERROR_REASON`), so SQL text and table names never reach Slack; the backoff is itself contained (a failing `retryLater` logs a second ERROR and leaves the row `SYNCING` for the stuck sweep), so a row that throws on every pass reaches `sync-max-attempts` and `FAILED` instead of looping. Row: random claim token → `claim`, which returns the claimed snapshot (miss → skip; no separate `find`, so the `change_seq` used later is the one the claim saw) → `loadSyncView`; the event should exist when the meeting is not canceled and the row's user is the host or an attending participant. Plan: wanted → write (below); not wanted with a stored `google_event_id` → `delete` of that id; not wanted, no stored id and the meeting still there → `delete` of `mirroredEventIdOf(meetingUid, slackUserId)`, because an insert that Google applied but whose answer was lost (timeout, 5xx) left no id behind; not wanted and the meeting row gone → `deleteSynced` with no token and no HTTP. Then `accessToken`: `NotConnected` → `markFailed("not connected")` on this row only, attempts unchanged (INFO, no DM, no other row touched); `Revoked` → **first** one `TransactionTemplate` runs `GoogleCalendarConnectionRepository.markRevoked(userId, observedEncryptedRefreshToken, now, cause.lastError)` and, **only when it matched**, `failPendingForUser` (the user's `PENDING` rows; the own row is `SYNCING` and untouched) and the cause's DM, while a miss (a reconnect, a disconnect or another worker changed the row first) writes nothing more and logs INFO; **then** `markFailed("google access revoked")` on the own row, outside any transaction; the user joins the tick's skip set in a `finally`, so the user's remaining rows in this tick are skipped before their claim (a `find` peek, only once someone was revoked) even when the revocation threw. `Misconfigured` → the tick pause below, hint "check `slack.app.calendar.google.client-id` and client-secret"; `Unavailable` → backoff. With a token: wanted and no event → `insert` under `mirroredEventIdOf(meetingUid, slackUserId)`, and `AlreadyExists` (409) → `patch` of that id; wanted with an event → `patch`, `Gone` → the same insert-or-patch. `Unauthorized` → `evict`, fetch again (any token outcome above can come back), repeat the call once. Outcomes: insert/patch `Ok` → `markSynced(observedSeq = the claim's change_seq, eventId)`; delete `Ok`/`Gone` → `deleteSynced`, a miss → `releaseWithoutEvent`; a miss of the completion CAS (`markSynced`, both `deleteSynced` and `releaseWithoutEvent`, `retryLater`, or `markFailed` returning `null`) means the claim was lost (see "A claim lost after a Google write" below): WARN, and when this pass had already sent a Calendar write, a re-queue of the pair; `RateLimited` → `retryLater` with the attempt count **unchanged**, due after `max(Retry-After, 2 min)`, `last_error` = `RATE_LIMITED_ERROR` ("rate limited by Google"), WARN, and the tick ends; `CalendarApiResult.Misconfigured` (the Calendar API is disabled for the client's project) → the tick pause, hint "enable the Google Calendar API in the OAuth client's Google Cloud project"; insert `Gone`, a stray `AlreadyExists`, a second `Unauthorized`, `Failed` → backoff. Tick pause: `retryLater` with the attempt count unchanged, due in one minute, `last_error` = the message, one ERROR naming the message and the hint, no DM, and the tick ends (so the ERROR repeats once per tick until the deployment is fixed). Backoff: `attempts + 1`; at `sync-max-attempts` → `markFailed(observedSeq, attempts + 1)` outside any transaction and, only when it reports `FAILED`, the failure DM in its own `TransactionTemplate` (`PENDING` means a hook changed the meeting during the last attempt: INFO, no DM; `null` means the claim was lost: WARN and, after a write, the re-queue); else `retryLater(observedSeq)` due after `min(60 min, 1 min × 2^(attempts-1))` (the exponent is capped at 6 so the 64-bit shift cannot wrap). Event body: `summary` = title, `description` = reason + blank line + `Managed by CodeCompanion (meeting <uid>)` (only the managed line for a blank reason), `start`, `end` = the view's `endAt` (already start + 1 h when the meeting has no valid end), the injected `Clock`'s zone, the meeting uid. Top-level `internal fun mirroredEventIdOf(meetingUid, slackUserId)` = lowercase hex SHA-256 of `"$meetingUid:$slackUserId"` (64 characters, inside Google's base32hex id alphabet `0-9a-v`, length 5–1024). The user is part of the input because two Slack users can connect the same Google account: with one id per meeting, one user's decline would delete the other's copy |
| `CalendarSyncScheduler.kt` | `@Component @Conditional(OnGoogleCalendarEnabled)`, `@Scheduled(fixedDelay = 60_000) tick()` → `containFailure { syncService.syncDue() }` with one ERROR `Google Calendar sync tick failed`, like `meeting/MeetingReminderScheduler` |
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
  `saveActive` and decrypted in two places only: `GoogleAccessTokenProvider`, to refresh an access token, and the
  revocation worker, to revoke the grant.
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
  `invalid_grant` on the first refresh; the sync worker turns that into `REVOKED` plus a "reconnect" DM.
  These checks look only at the requesting Slack user's connection (the worker's re-read and the `SCOPE_DENIED`
  check both call `find(userId)` for that user). So when two Slack users connected the same Google account, one
  user's disconnect, switch to another account or `SCOPE_DENIED` revoke is expected to revoke the other user's grant
  too (Google's documented grant-wide revoke, not observed here); that user's next token refresh then answers `invalid_grant` and goes through the same `REVOKED` path
  (accepted 2026-10-11, `docs/wiki/decisions.md` #36).
- **Disconnect clears the user's consent states, connected or not.** `deleteForUser` removes every state row of
  the user, consumed ones included, before the no-connection check. A consent link issued before `disconnect` and
  clicked after it would otherwise connect the account without a fresh `connect`, including the link of a user who
  ran `connect` and then `disconnect` before opening it, when no connection row exists yet.

#### Mirror sync
- **A queue row is a dirty marker; the worker decides.** The hooks only say "look at this (meeting, user)
  again"; `CalendarSyncService` re-reads `loadSyncView` after its claim and derives insert / patch / delete from
  the meeting as it is then. Do not pass a desired action through the queue (see `repository/calendar/AGENTS.md`).
- **Hooks run inside the meeting write transactions** (`service/meeting/AGENTS.md` says where), so a hook that
  throws rolls the meeting write back. They never call Google; all HTTP happens on the scheduler thread outside any
  transaction. The worker's only transactions are the repository methods' own, the `TransactionTemplate`s that
  stage a DM and the one that re-queues a pair after a lost claim (the scheduler thread has none). The queue's worker transitions (`claim`, `markSynced`,
  `deleteSynced`, `releaseWithoutEvent`, `retryLater`, `markFailed`, `resetStuck`) throw when called inside a
  transaction, so `markFailed` runs first on its own and the failure DM follows in a separate transaction: a crash
  between the two leaves the row `FAILED` with its `last_error` and no DM. The revocation is the exception that
  shares one transaction: `markRevoked`, `failPendingForUser` (a hook-side method) and the reconnect DM commit
  together, so the DM goes out exactly when the connection became `REVOKED` (`CalendarSyncServiceTest` pins all three
  on one H2 connection and `markFailed` outside it). The hook methods refuse to run outside a transaction.
- **How a row ends.** (1) Deleted: `deleteSynced` once no event is wanted and Google confirmed none is left (a
  delete answered `Ok` or `Gone`, under the stored id or, with none stored, under the derived one), or with nothing
  to delete because the meeting row is gone. (2) `SYNCED` with the event id: `markSynced` while `change_seq` still
  equals the claim's value; a moved seq makes it `PENDING` again with the id kept. (3) `FAILED`: `markFailed` at
  `sync-max-attempts` (storing the attempt count it reached), on `NotConnected` or on `Revoked`, each only while
  `change_seq` still equals the claim's value; `failPendingForUser` for the user's `PENDING` rows when the worker's
  `markRevoked` matched ("google access revoked") or when the user disconnects ("disconnected"). Any hook touch, and a
  reconnect's `touchForUser` (for a meeting the reconnect's range read returns), makes a `FAILED` row `PENDING` again. Everything else returns the row to `PENDING`:
  `retryLater` (a backoff, a rate limit or a tick pause), `markFailed` / `retryLater` after a hook moved the seq
  (attempts 0, due at once), `releaseWithoutEvent` (a confirmed delete whose `deleteSynced` missed on the seq), the
  stuck sweep, and the re-queue of a worker that lost its claim after a Google write (an `enqueue`, so a row another
  pass already deleted comes back as a new `PENDING` row).
- **Unexpected exceptions count as attempts.** An exception after the claim (a repository call, a mapping bug, a
  failed revocation transaction) backs the row off with the exception's message as `last_error`, so
  `sync-max-attempts` ends a row that throws on every pass with `FAILED` and the failure DM ("a meeting" when the
  view was never loaded, and "an internal error" as the reason, never the exception text) instead of a loop through
  the stuck sweep. Only when that backoff fails too (the database is down) is the row left
  `SYNCING` for the sweep.
- **`NotConnected` fails only the claimed row.** That covers a `REVOKED` connection and a disconnected user. The
  worker settles the row it owns and leaves every other row of the user to its own pass. The events already
  mirrored stay in that user's calendar: there is no token left to delete them with.
- **Disconnect keeps the rows.** `disconnect` fails the user's `PENDING` rows with "disconnected" and leaves `SYNCED`
  and `FAILED` rows (and a `SYNCING` one, which its worker settles, usually as `NotConnected`), because they are
  what a later connect revives with `touchForUser` when that connect's range read returns their meeting; a hook that touches one meanwhile sends it through the worker,
  which fails it as `NotConnected`.
- **The reconnect DM goes out once per grant.** The provider only reports `Revoked` with the ciphertext it read and a
  `RevocationCause`; the worker's `markRevoked` matches only while the row is `ACTIVE` with that ciphertext, and only
  that match fails the user's `PENDING` rows and sends the DM, chosen per cause by an exhaustive `when`:
  `GRANT_REJECTED` → `GRANT_REJECTED_MESSAGE` ("Google no longer accepts CodeCompanion's access to your calendar (it
  was revoked or expired), so meetings are no longer mirrored. Run `/calendar connect` to reconnect."),
  `TOKEN_UNREADABLE` (for example after a key rotation; reconnecting is the only recovery) →
  `TOKEN_UNREADABLE_MESSAGE` ("CodeCompanion can no longer use your saved Google Calendar connection, so meetings are
  no longer mirrored. Run `/calendar connect` to reconnect."). The connection's `last_error` is the cause's
  `lastError`. Later ticks see the `REVOKED` row as `NotConnected`. The revocation commits before the own row is
  failed, so a revocation transaction that throws leaves the own row unfailed (it backs off and the next pass tries
  the revocation again). When a reconnect replaced the token before `markRevoked`, nothing else is failed and the
  own row is still failed; the reconnect's `touchForUser` makes it `PENDING` again when the reconnect's range read returns its meeting (or, when it committed first,
  `markFailed` already saw the moved seq and requeued it).
- **Deployment-wide failures pause the worker, not the rows.** `invalid_client` / `unauthorized_client` on a refresh
  describe the deployment's `client-id` / `client-secret`, and a 403 `accessNotConfigured` / `SERVICE_DISABLED` from
  the Calendar API says the API is disabled in the client's Cloud project; neither is the user's fault, so no row
  burns an attempt, nobody gets a DM, and each tick stops at the first such row with one ERROR. A rate limit is not
  the row's fault either: it requeues the row with its attempt count unchanged (so `sync-max-attempts` never fails a
  row for it), after at least two minutes, and ends the tick. A hook that moves `change_seq` during the rate-limited
  call makes `retryLater` requeue the row at once with attempts 0 (its `CASE`), so the two-minute floor applies only to
  unchanged rows; the tick stop still throttles it.
- **Connecting re-queues the user's meetings.** `storeConnection`, in the same transaction as `saveActive`, enqueues
  the meetings the user **hosts** that are not canceled and have not ended (start from a day ago, so a meeting in
  progress counts, up to two years ahead), then `touchForUser` revives the user's existing queue rows of every meeting
  that read returned (hosted, attended or canceled), `FAILED` ones included; rows of meetings outside the read stay as
  they are. After a
  revoke-then-reconnect, a disconnect-then-reconnect or a switch to another Google account every revived row is
  synced again; in a new account the stored event id answers 404 and the deterministic id is inserted, while past
  meetings are not recreated there. The bound is on the start, so a row of a meeting that started within the last
  day is revived even when that meeting has already ended. The old account keeps the copies already mirrored (there is no token
  left to delete them). Meetings the user only attends are not enqueued: `meeting_participants.is_attending` is
  `true` from the invitation on, so the back-fill cannot tell an accepted invitation from an unanswered one, and an
  attendee gets an event only once they press Approve (the hook then enqueues it). **Known limit:** a meeting the
  user accepted before ever connecting has no queue row, so the first connect does not mirror it. Recording the
  answer time (a `responded_at` column, option (a) in `.omc/plans/google-calendar-user-oauth.md` 2-C) would allow it
  and is a recorded candidate, not implemented.
- **The failure DM** reads "CodeCompanion couldn't sync *<title>* to your Google Calendar (<reason>). It will not
  retry; reconnect with `/calendar connect` if this keeps happening." — title and reason (first 200 chars)
  through `escapeMarkup`, "a meeting" when the meeting is gone. The reason is the backoff's user-facing one: the
  fixed text or Google's own message for an API or token outcome, and "an internal error" for an unexpected
  exception, whose raw message stays in `last_error` and the logs (`backoff`'s `userReason`, defaulting to the
  diagnostic `reason`). It goes out only when `markFailed` reports
  `FAILED`; a row that `markFailed` put back to `PENDING` (a hook moved the seq during the last attempt) is retried,
  so "It will not retry" would be false.
- **Budget and pool.** The scheduler pool is 4 threads shared with every job. `sync-tick-budget-seconds` (30) is
  checked before each row, so a tick overruns it by at most one row; one row makes up to six Calendar calls
  (patch → 404 → insert → 409 → patch, repeated once after a 401) plus two token refreshes, each bounded by
  `request-timeout-seconds` (10), about 80 s. `sync-stuck-minutes` (10) stays well above that, and
  `AppConfig.Calendar.Google` refuses to bind unless `sync-stuck-minutes × 60 > 8 × request-timeout-seconds + 60`. A
  reset of a row still in flight cannot duplicate an event: the event id is derived from the meeting and the user, so
  the next pass re-inserts the same id, gets 409 and patches. It can still leave Google wrong, which the next bullet
  handles.
- **A claim lost after a Google write re-queues the pair.** Prod runs two replicas, and a worker can stall (GC, CPU
  starvation) between reading the meeting and its Calendar call for longer than `sync-stuck-minutes`. The other
  replica then resets and reclaims the row and settles it from the newer state; the stalled write lands afterwards
  and its completion CAS misses: an insert after the other pass deleted the event and the row (an orphan nothing
  points at), a patch with the old time over a newer reschedule the other pass recorded as `SYNCED`, or a delete of
  an event the other pass just re-created. So once a pass has sent a Calendar write (`mirror` sets the claim's
  `googleWriteIssued` before the first call, whatever the answer, since a timeout or 5xx may still have landed), any
  later miss of its own completion CAS (`markSynced`, `deleteSynced` + `releaseWithoutEvent`, `retryLater`,
  `markFailed` → `null`) checks `hasActiveConnection(userId)` and, when the user is still connected, runs
  `enqueue(meetingId, userId, now)` in its own `TransactionTemplate` transaction (the worker transitions refuse an
  outer transaction; the upsert needs one). The upsert recreates a deleted row or bumps `change_seq` on a live one
  (a row another worker holds `SYNCING` then completes as `PENDING`), so the next pass recomputes the desired state
  from the meeting and converges: the orphan is deleted under its derived id, the newer time is patched back.
  WARN in every branch; a `DataIntegrityViolationException` (the meeting row is gone, so the queue's foreign key
  refuses the insert) is logged at WARN and nothing is re-queued; an unconnected user is not re-queued (no token to
  reconcile with, and the next connect's back-fill covers hosted meetings). A miss with no Calendar write behind it
  (a settle with no HTTP, a token failure) only logs WARN. Pinned by `CalendarSyncServiceTest`, including two
  two-replica scenarios on the real queue adapter (H2 in `MODE=MariaDB`) with a fake Google calendar.
- **Token cache.** One access token per user in memory, reused until 60 s before `expires_in`, dropped on
  `Unauthorized` (`evict`, then one retry). It is bound to the stored refresh token: the connection row is read on
  every request (one indexed `SELECT` per row, cheap next to the HTTP calls), so a disconnect answers
  `NotConnected` at once and a reconnect, which always stores a new ciphertext, forces a refresh with the new grant
  instead of writing into the replaced account's calendar. The row is read once more after each refresh, so a token
  minted from a grant that a disconnect, revoke or reconnect replaced during the HTTP call is neither cached nor used.
- **Accepted races (MariaDB snapshot isolation, error 1020).** The cancel and reschedule hooks sit inside the meeting
  write's `executeRetryingOnConflict`; these queue writes do not, and each can fail with 1020 in a window
  milliseconds wide: (1) the accept / decline hook, through the Accept upsert's foreign-key check on `meetings` when
  the host changed the meeting after the interaction transaction's snapshot, or through its own row (the upsert, or
  `touchOne` on Decline) when the worker wrote it after that snapshot (the click rolls back and the user clicks
  again; `repository/calendar/AGENTS.md`); (2) the connect back-fill's hosted upsert, through its foreign-key check
  on `meetings` when the meeting changed after the store transaction's snapshot, or through its own row when the
  worker wrote it after that snapshot; (3) `disconnect`'s `failPendingForUser` and (4) the back-fill's revive
  `touchForUser`, when the worker wrote one of the user's rows after the slash or store transaction's snapshot (for
  example the `NotConnected` failure of a revoked user's row). A failed disconnect rolls back and the user runs it
  again; the callback retries the whole store once and, when the conflict repeats, answers `STORE_FAILED` and the
  user connects again. The revive no longer reads `meetings`: it takes the explicit ids of the back-fill's own read,
  so it locks only the user's queue rows.
- **Not yet run against MariaDB or Google.** The specs here mock the queue, except the two-replica scenarios in
  `CalendarSyncServiceTest` and one `service/meeting/MeetingWriteJpaTransactionTest` case, which run the real queue
  adapter on H2 in `MODE=MariaDB`; the queue's SQL is tested on that mode in `:infrastructure`. Google's 409 on a repeated id, its 404 on a patch of an id
  from another account, the 403 body for a disabled Calendar API (`accessNotConfigured` / `SERVICE_DISABLED`, shaped
  after Google's documented error model), the 1020 windows above and the live check belong to slice 3.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.calendar.*'
./gradlew :application:test --tests 'dev.notypie.application.configurations.CalendarConfigurationTest'
./gradlew :application:test --tests 'dev.notypie.application.service.meeting.*'
```
`MeetingCalendarMirrorServiceTest` (every hook inside a real H2 transaction, and the guard outside one),
`GoogleAccessTokenProviderTest` and `CalendarSyncServiceTest` (MockK ports, fixed
or `MutableClock`, `createStubTransactionManager()` or, where a case asserts transaction boundaries, a real H2
`createH2TransactionManager()`, infrastructure fixtures `createMeetingCalendarEvent` /
`createCalendarMeetingView` / `createCalendarEventBody`; the two-replica scenarios use
`service/meeting/MeetingTransactionFixtures.createH2MeetingJpaStore(mariaDbMode = true)`) cover the mirror; the test directory's `AGENTS.md` lists
the cases. The hooks' placement inside the meeting write transaction is pinned in `service/meeting/` specs.
`CalendarConnectionServiceTest` builds the service with MockK repositories, `GoogleOAuthClient` and
`ApplicationEventPublisher`, a real `TokenCipher` from `createTestTokenCipher()` and connection records from
`createCalendarConnection` (both infrastructure testFixtures, `dev.notypie.schema`), a fixed `Clock`,
`createStubTransactionManager()` and an `AppConfig` whose `calendar.google` is enabled; it asserts the staged
DM / ephemeral shapes, the issued state shape and TTL, the per-user state delete on disconnect with and without a
connection row, the `PENDING`-only queue failure on disconnect, the published revocation events (same account → none, other account by subject or e-mail → one,
unknown → none; each carries the replaced subject), the one-shot store retry on a unique-key collision or a snapshot
conflict and `STORE_FAILED` when the conflict repeats, the decrypted stored token, every `completeConnection` outcome
and the connect back-fill (range, the hosted-only and not-ended filter, `touchForUser` once with every id the read returned, an empty list included, order, and one H2
transaction for the save, the meeting read, the enqueue and the revive). `GoogleTokenRevocationWorkerTest` drives the worker with a same-thread `Executor`
(confirmed / unconfirmed / undecryptable / rejected / a task that throws before the revoke, and the reconnect skip by
subject).
`GoogleTokenRevocationWorkerTransactionTest` publishes the event through a small `AnnotationConfigApplicationContext`
over a real H2 `DataSourceTransactionManager` (`service/meeting/MeetingTransactionFixtures`): the executor receives
the task only after commit, a rollback hands off nothing, and the rejection-path DM runs on a connection other than
the committed transaction's. Events come from `createCalendarConnectionRequestEvent` (domain testFixtures).

## Dependencies

### Internal
- `domain/command/entity/slash/CalendarCommand` (`/calendar`); `application/service/command/CommandExecutor`, `application/common/IdempotencyCreator`
- `domain/command/entity/event/` — `CalendarConnectionRequestEvent`, `CalendarConnectionPayload`, `CalendarConnectionAction`
- `infrastructure/impl/calendar/` — `GoogleOAuthClient` (`CALENDAR_EVENTS_SCOPE`, `refresh`), `GoogleOAuthException`, `GoogleTokenGrant`, `GoogleAccessToken`, `TokenCipher`, `GoogleCalendarClient`, `CalendarApiResult`, `CalendarEventBody`
- `infrastructure/repository/calendar/` — `GoogleCalendarConnectionRepository` (`ConnectionSaved`, `hasActiveConnection`, `markRevoked`), `GoogleOAuthStateRepository`, `MeetingCalendarEventRepository` (`CalendarMeetingView`), `schema/CalendarConnection`, `schema/MeetingCalendarEvent`
- `infrastructure/repository/meeting/MeetingRepository` (`findMeetingId`, `getMeetingsByUserIdInRange`); `application/service/standup/containFailure`
- `application/configurations/` — `AppConfig.Calendar.Google`, `CalendarConfiguration`, `CalendarDisabledConfiguration`, `conditions/OnGoogleCalendarEnabled`
- Consumers: `application/controllers/SlashCommandController` and `application/socket/SocketModeReceiver` (through
  `CalendarSlashService`); `application/controllers/GoogleOAuthCallbackController` (through `CalendarConnectionCallback`);
  `application/service/meeting/MeetingServiceImpl` and `MeetingRescheduleService` (through `MeetingCalendarMirror`)

### External
Spring context events, transactions and scheduling, kotlin-logging, `java.security.SecureRandom`, a `java.util.concurrent.Executor`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
