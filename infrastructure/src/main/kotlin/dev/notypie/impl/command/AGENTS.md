<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# infrastructure/impl/command

## Purpose
The Slack transport adapter. Inbound: raw Slack interaction JSON → `InteractionPayload` → neutral
`InboundInteraction`, and domain `CommandIntent`s → `CommandEvent`s. Outbound: domain `OutboundMessage`s
are staged (modals rendered now, everything else enqueued) and rendered at deliver time into Slack Web API
form bodies that `ApplicationMessageDispatcher` sends. `EventPublisher` implementations live here too.

## Key Files
| File | Description |
|------|-------------|
| `SlackInteractionRequestParser.kt` | `InteractionPayloadParser` impl. Peeks the top-level `type`, then Gson snake_case into the SDK `ViewSubmissionPayload` or `BlockActionPayload`. `parseStates` maps `view.state.values` to `States` (with `blockId`); view_submission synthesizes an `APPLY_BUTTON` `currentAction`, reads routing tokens from `private_metadata`, injects an empty `STATIC_SELECT` for `MEETING_DECLINE_REASON`, and recovers the delivery channel from `routingExtras[1]` for reschedule/add-participant. block_actions reads tokens from `message.text` (or the button value on ephemeral primaries); `buttonParser` maps style `primary`/`danger` to `APPLY_BUTTON`/`REJECT_BUTTON` |
| `InteractionPayloadParser.kt` | `interface InteractionPayloadParser { parseStringPayload(payload: String): InteractionPayload }` |
| `SlackInboundMapper.kt` | `InteractionPayload.toInbound()` / `toInboundCommand()`, `States.toInboundField()` / `toInboundAction()`, `ActionElementTypes → InboundFieldKind`; `buildSubmission` builds the typed `InboundSubmission` for the seven `*_SUBMIT` / `MEETING_DECLINE_REASON` detail types, sorting standup answers by `standup_q_<index>` |
| `SlackIntentResolver.kt` | `resolveAll(intents, basicInfo)`: exhaustive `when` over `CommandIntent` (21 variants; `Nothing` → dropped) producing the matching `CommandEvent` and its routing `CommandDetailType` |
| `SlackOutboundStager.kt` | `OutboundMessageStager` impl. `OpenModal` → `stageModal` (seven `ModalForm` variants; blank `trigger_id` → `null` + warn; `StandupFill` loads routine and session via `StandupRepository`); every other family → `OutboundMessageEnqueued`, unrendered |
| `OutboundRenderer.kt` | `OutboundRenderer` port + `SlackOutboundRenderer`: `OutboundMessage` → `SlackEventPayload` via the constructor; `OpenModal` / `DirectMessage` and not-yet-migrated `MessageContent`s hit `error(...)` |
| `SlackApiEventConstructor.kt` | Builds `SendSlackMessageEvent` (message/ephemeral/action-response/`chat.update`) and `OpenViewEvent` (seven `open*ModalRequest`s) from `SlackTemplateBuilder` layouts. SDK requests become form maps via `RequestFormBuilder.toForm`; `buildRoutingText` writes `"<idempotencyKey>,<CommandDetailType>[,urlencoded extras…]"` into `message.text` |
| `ApplicationMessageDispatcher.kt` | `MessageDispatcher` impl. Constructor defaults are the production clients: `slack = slackClient()` (`SlackConfig` with `statsEnabled = false` and `httpClientCallTimeoutMillis = SLACK_CALL_TIMEOUT` = 6 s) and `okHttpClient = responseUrlClient(slack)` (`slackOkHttpClient` with the same call timeout plus `followRedirects(false)`, `followSslRedirects(false)`); `slackClient(config = slackConfig())` hands the SDK the same `slackOkHttpClient` through `SlackHttpClient`; specs pass their own `slack`, `okHttpClient` and `sleeper`. `onOutcomeUnknown(slackMethod)` and `onAccessBlocked(slackError)` have no default: the first is called once for every outcome-unknown result with `chat.postMessage`, `chat.postEphemeral` or `response_url`, the second once for every access-blocked result with the Slack code, and the application counts both. `dispatch` routes `PostEventPayloadContents.messageType` to `chat.postEphemeral` / `chat.postMessage` / `chat.update` via `postFormWithTokenAndParseResponse`, and `ActionEventPayloadContents` to an OkHttp POST on `response_url`; `OpenViewPayloadContents` throws before any retry. `dispatchImmediate` runs `views.open` on the caller's thread and never throws: a rejection or exception publishes `DeclineModalOpenFailedEvent` / `StandupModalOpenFailedEvent` when the detail type has one, and returns `failOutput`. Also declares `RATE_LIMITED_REASON` / `isRateLimited()`, `TRANSIENT_EXHAUSTED_REASON` / `isTransientExhausted()`, `ACCESS_BLOCKED_REASON` (`"access_blocked"`, no suffix) / `isAccessBlocked()` — the outcome the relay holds instead of failing, for a Slack refusal of the whole token or workspace —, `RateLimitedOutput(event, retryAfter)` and `retryAfter()`. Decision table below |
| `SlackViewOpenDispatcher.kt` | Also declares `ViewOpenDeferral`: a caller that owns a transaction boundary wraps it in `ViewOpenDeferral.afterBoundary { … }`, and every `views.open` staged inside runs after the block returns, i.e. after the transaction committed and released its pooled connection (a JPA transaction keeps the connection through `afterCommit`/`afterCompletion`, so an after-commit listener would not release it). A failed block opens nothing. Outside such a block the open runs immediately. Synchronous (non-`@Async`) `@EventListener` for `OpenViewEvent` → `dispatchImmediate` |
| `KafkaEventPublisher.kt` | `EventPublisher`: `isInternal` → Spring bus, else `kafkaTemplate.send(destination, idempotencyKey, payload)` awaited `sendTimeoutMillis` (default 5000). A timeout, a checked execution cause or an interrupt (flag restored) is thrown as the unchecked `KafkaPublishException`, an unchecked cause as it is: Spring's `@Transactional` commits on a checked exception, so the old rethrow of `TimeoutException` let the caller commit after a failed publish (2026-10-02). Dormant today (every event is internal); a broker send inside the command transaction can still go out for a transaction that later rolls back, so a future external event belongs in the outbox |
| `AppEventPublisher.kt` | `EventPublisher` that publishes every event on the Spring bus (default `APPLICATION_EVENT` mode) |
| `RestRequester.kt` / `RestClientRequester.kt` | Generic Spring `RestClient` wrapper: `safe*` verbs return `Result<ResponseEntity<T>>`, plain verbs `bodyOrThrow`; per-call bearer header; `SLACK_API_BASE_URL`; built from the `RestClient.Builder` constructor param (the bean passes Boot's, which carries the observation customizer, so its calls are recorded as `http.client.requests`; the default static `RestClient.builder()` records nothing) with an explicit `JdkClientHttpRequestFactory`, `connectTimeout = 3s` / `readTimeout = 10s` (constructor params), which replaces whatever factory the builder brought. `safeGet` takes `uriVariables`, expanded and encoded by Spring's URI template (never interpolate caller values into `uri`). Only consumer: `templates/SlackUserProfileResolver` (`users.profile.get?user={user}`), fed through `ModalTemplateBuilder` with the `restRequester` bean from `application/configurations/RestClientConfiguration` (default timeouts) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `slack/` | Slack wire DTOs, `ActionElementTypes`, `InteractionTypes`, mention and slash-command mappers (see `slack/AGENTS.md`) |
| `event/` | `SlackEventPayload` family, `SendSlackMessageEvent` / `OpenViewEvent` / `OutboundMessageEnqueued`, `MessageDispatcher`, output helpers (see `event/AGENTS.md`) |
| `dto/` | `SlackUserProfileDto` for `users.profile.get` (see `dto/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **The routing-text format is a two-sided contract.** Writer: `SlackApiEventConstructor.buildRoutingText`
  (extras URL-encoded, joined with `,`) for `chat.postMessage`, and `private_metadata` for modals. Reader:
  `SlackInteractionRequestParser` splits on `,`, trims, `URLDecoder`s each extra, and `CommandDetailType
  .valueOf`s token 1 — an unknown name throws. `chatPostEphemeralBuilder` writes `"$key, $type"` with no
  extras and no encoding; on an ephemeral block_action the embedded text is the *button value*, so an
  ephemeral primary button must carry the routing string as its `value`.
- **`routingExtras` positions are per detail type** (`SlackInboundMapper.buildSubmission`): `[0]` =
  requester / participant / creator / user id, `[1]` = delivery channel (reschedule, add-participant) or
  notice channel (decline, standup answer) or command channel (standup setup), `[2]` = notice `ts`. The
  parser's `recoverDeliveryChannel` reads `[1]` only for the two meeting-host flows.
- **`chat.postEphemeral` needs `channel` = the channel and `user` = the viewer.** A user id in `channel`
  routes the ephemeral into that user's DM. For DMs, `chatPostMessageBuilder` sets `channel = targetUserId`
  and the dispatcher treats `DIRECT_MESSAGE` exactly like `CHANNEL_ALERT` (`chat.postMessage`).
- **Dispatch decision table** (`dispatch` returns one of these outcomes; the outbox relay relies on it). A
  Slack `error` code is classified once, by `raiseIfRetryable`, for both `chat.*` and `response_url` bodies:
  `ratelimited` → rate limited, `TRANSIENT_SLACK_ERRORS` → transient; then `SLACK_ACCESS_ERRORS` → access
  blocked (`chat.*` only), anything else → permanent.
  - Rate limited — `chat.*` HTTP 429, `chat.*` `ok=false error=ratelimited`, `response_url` HTTP 429 or JSON
    `{"ok":false,"error":"ratelimited"}` → `SlackRateLimitedException`, handled outside `RetryService`. If
    `Retry-After` (seconds or HTTP-date, clamped to `[0, MAX_RETRY_AFTER]` = 24 h when parsed, the outbox
    give-up bound, so a huge value cannot overflow the relay's `LocalDateTime` arithmetic) is ≤
    `MAX_INLINE_RETRY_AFTER` (3s) the thread waits once and calls again; a larger or missing `Retry-After`, a second rate limit, or an interrupt during the wait (flag
    restored) returns `RateLimitedOutput(retryAfter)` at once (`isRateLimited()`, `retryAfter()`). The relay
    defers the row past `Retry-After`. The CDC listener thread must never sleep long.
  - **Was the request written?** Every production OkHttp client is built by `slackOkHttpClient(config)`:
    `retryOnConnectionFailure(false)` (OkHttp would otherwise re-send a written form body after a reset on a
    pooled connection, before the dispatcher sees anything) and `RequestProgressListener`, which records on the
    calling thread whether the attempt finished writing its request headers (`requestHeadersEnd`, not
    `requestHeadersStart`: on HTTP/2 the header write opens the stream, and a pooled connection that already got
    `GOAWAY` fails there with `ConnectionShutdownException` before anything is sent). An `IOException` from an
    attempt that never got there is rethrown as `SlackRequestNotSentException`, whatever OkHttp wrapped it in (a connect or DNS
    failure that ends after the call timeout surfaces as `InterruptedIOException("timeout")` with the real error
    as its cause). A client without the listener counts as "may have been written". A stream the server resets with
    `REFUSED_STREAM` (OkHttp 4.12 `StreamResetException.errorCode`; a `GOAWAY` also fails every stream above its last
    good id this way) is rethrown as `SlackRequestNotSentException` too, even after the headers went out: HTTP/2
    guarantees the server did not process it. A 2xx body the SDK cannot read (Gson `JsonParseException` for a non-JSON body, an
    NPE for an empty one) becomes `SlackResponseUnreadableException`, an `IOException`: Slack has answered, so a post
    ends as outcome unknown and the idempotent `chat.update` is retried. The catch wraps only the SDK call and only
    after `requestHeadersEnd`, so an NPE in our own code (before sending, or while mapping the response) is not
    mislabelled. An `ok=false` without an `error` field is a plain rejection with `UNSPECIFIED_ERROR_REASON`. Classification after the
    retries walks the cause chain, as Spring's retry policy does.
  - Transient — retried by `RetryService` (3 attempts), then `failOutput(TRANSIENT_EXHAUSTED_REASON)`
    (`isTransientExhausted()`); the relay leaves the row `IN_PROGRESS` and the recovery sweep re-sends it, up to
    `outbox.polling.max-sends` sends. For `chat.postMessage`, `chat.postEphemeral` and `response_url`: a request
    that was never written or whose stream was refused, `ok=false service_unavailable`, and HTTP 503. For the idempotent `chat.update`: also
    every other `IOException` (the call timeout included), any HTTP 5xx and `internal_error`.
  - Outcome unknown — on the three non-idempotent calls, an `IOException` after the request was written (call
    timeout, reset, dropped connection), `ok=false internal_error` (Slack: "possible some aspect of the operation
    succeeded"), and HTTP 5xx other than 503 → no retry, `failOutput("$OUTCOME_UNKNOWN_REASON: <type|code>")`, and
    the relay writes `FAILURE`. Slack may already have posted the message and has no idempotency key, so
    resending would duplicate it; the row and the warning log (which names the Slack method) are the
    reconciliation record, and `onOutcomeUnknown` feeds the per-method counter. Treating 503 as not
    processed is a judgement from its HTTP meaning, not something Slack documents.
  - Access blocked — `chat.*` `ok=false` with a code from `SLACK_ACCESS_ERRORS`, the token- or workspace-wide
    refusals of the docs.slack.dev `chat.postMessage` error table (`invalid_auth`, `not_authed`, `account_inactive`,
    `token_revoked`, `token_expired`, `missing_scope`, `not_allowed_token_type`, `team_access_not_granted`,
    `accesslimited`, `org_login_required`, `team_added_to_org`) → ERROR log, `onAccessBlocked(code)`, no retry,
    `failOutput(ACCESS_BLOCKED_REASON)` (`isAccessBlocked()`, no suffix). The relay holds the row (the hold
    time is the relay's) instead of failing it, so a token rotation does not lose the backlog. Codes the table
    scopes to one channel stay permanent: `ekm_access_denied` (documented both as "disabled sending messages to
    this channel" and as a suspension, so it is not taken as global), `no_permission` ("make sure your app is a
    member of the conversation"), `not_in_channel`, `is_archived`, `restricted_action*`, `access_denied`.
  - Permanent — any other `ok=false` (including `fatal_error`, which may have partly succeeded, and
    `request_timeout`, a truncated POST), `chat.*` non-429 HTTP 3xx/4xx (`http_<code>: <body prefix>`, no retry)
    and `response_url` 3xx / 4xx / JSON `ok=false` / a 2xx body that is not an acknowledgement →
    `failOutput(<error>)`, once. The relay writes `FAILURE`.
  - Anything else (a non-transient exception inside the retry, or thrown outside it) propagates as-is; the
    relay treats it like a transient outcome.
- **`Retry-After` parsing is complete in production** because `slackClient()` turns SDK stats off: with stats on
  the SDK's own catch block runs `Long.valueOf(Retry-After)` before rethrowing (an HTTP-date would escape as
  `NumberFormatException`), and it resolves the team id with an extra `auth.test` call, which is retried on
  every call while Slack is down. Nothing here reads the SDK metrics, which only feed the async rate limiter.
- **A whole dispatch is time-bounded**, so it always ends before the Kafka per-record budget and long before
  `outbox.polling.stuck-in-progress-seconds` (300 s) lets the sweep reclaim the row mid-send. Worst case:
  each HTTP call ≤ `SLACK_CALL_TIMEOUT` 6 s (OkHttp `callTimeout` spans DNS, connect, write, server time and
  the whole body); one `RetryService` run is 3 calls + backoff ≤ 0.1 + 0.2 s + 2 × 10 ms jitter = 18.32 s; with
  the single inline rate-limit wait (≤ 3 s) and the second run, `dispatch` ≤ 18.32 + 3 + 18.32 = 39.64 s.
  Render adds at most one `users.profile.get` (`RestClientRequester` connect timeout 3 s + read timeout 10 s,
  which Spring's JDK factory applies to the whole exchange including the body): ≤ 52.64 s of HTTP per record. The CDC consumer
  runs `max-poll-records: 5` under `max.poll.interval.ms: 300000`, i.e. 60 s per record, which leaves ≥ 7 s
  for the claim, renew and completion SQL. Raising `SLACK_CALL_TIMEOUT`, the retry attempts, the inline wait
  or `max-poll-records` must keep this sum below 60 s.
- **`response_url` is validated before any request**: `https`, port 443 and a host in `SLACK_RESPONSE_URL_HOSTS`
  (`hooks.slack.com`, GovSlack `hooks.slack-gov.com`), checked on the parsed `HttpUrl` that is then sent, so
  userinfo (`https://hooks.slack.com@evil.example/…`) and a trailing dot are rejected and upper case is
  canonicalised; anything else is `failOutput("response_url_rejected: …")`. The client never follows
  redirects, so the allowlist is final and a 3xx is a permanent failure. Only the first 4 KiB of the response
  are read (`peekBody`); success is plain-text `ok` or JSON `ok=true`, and any other 2xx body (an HTML page, an
  empty body) is a permanent `unexpected_body: http_<code>: <body prefix>` failure, once, never resent.
- The invalid `PostEventPayloadContents`/action-response pairing is unrepresentable — `MessageType` has no
  `ACTION_RESPONSE`.
- **`dispatchImmediate` fallbacks need `participantUserId`.** Every `open*ModalRequest` sets it (requester,
  creator, or publisher); blank means no failure event is published. Only `MEETING_DECLINE_REASON` and
  `STANDUP_PROMPT` have a fallback event — other modal failures are logged and returned as `failOutput`.
- **`SlackOutboundRenderer` has `else -> error("not yet migrated")` branches** for `ChannelMessage` and
  `Ephemeral` content kinds it does not render (`MeetingList` on a channel message, anything but
  `Text` / `MeetingList` on an ephemeral). A new `MessageContent` variant needs a branch here, a mix-in
  in `repository/outbox/OutboundMessageCodec`, and a template — or the outbox row sticks at deliver time.
- **`RestClientRequester.kt` declares a public top-level `val logger`** in this package. Every other file
  uses a private `log` / `dispatcherLog`; adding another top-level `logger` here is a redeclaration error.
- `SlackIntentResolver` maps several intents (`GrantRole`, `RevokeRole`, `ListRoles`, CVE ops) to
  `CommandDetailType.SIMPLE_TEXT` — routing is by event class, the detail type there is informational.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.*'
```
Specs: `SlackInteractionRequestParserTest`, `SlackInboundMapperTest`, `SlackIntentResolverTest`,
`SlackOutboundStagerTest`, `SlackOutboundRendererTest`, `SlackApiEventConstructorTest`,
`ViewSubmissionChannelRoutingRegressionTest` (guards the `private_metadata` channel recovery — never
delete), `KafkaEventPublisherTest` (`@SpringBootTest` + `EmbeddedKafka`), `RestClientRequesterTest`,
`ApplicationMessageDispatcherTest` (a `com.sun.net.httpserver` fake Slack with `SlackConfig.methodsEndpointUrlPrefix`
and `statsEnabled = false` — with stats on the SDK calls `auth.test` first and eats the queued response — plus
`responseUrlClient(...)` with an OkHttp interceptor that redirects `https://hooks.slack.com` to the fake; covers
the whole decision table, the call timeout, the production client settings and the redirect / allowlist cases).
Fixtures: `testFixtures/.../impl/command/BlockActionPayloadCreator`, `slack/InteractionPayloadCreator`,
`slack/SlackEventCallBackRequestCreator`, `event/SlackEventTestFixtures`. There is no spec for
`AppEventPublisher`; `SlackViewOpenDispatcherTest` pins the deferral (opened after the H2 transaction, never for a failed block, immediately without a boundary).

### Common Patterns
- Exhaustive `when` over domain sealed types (`CommandIntent`, `OutboundMessage`, `ModalForm`) — no
  `else` except the explicit `error(...)` guards in the renderer.
- Slack SDK types stay inside this package: build with SDK request builders, flatten to `Map` /
  `String` bodies before the payload leaves.
- `runCatching` + `fold` around SDK calls that must not throw (`dispatchImmediate`); named arguments.

## Dependencies

### Internal
- `domain/command/*` — `CommandIntent`, `OutboundMessage`, `ModalForm`, `MessageContent`, inbound model,
  `CommandEvent` / `EventPayload` / `EventPublisher`, `CommandBasicInfo`, `CommandDetailType`,
  `CommandOutput`, modal-open-failed events
- `impl/command/event`, `impl/command/slack`, `impl/command/dto`, `impl/retry/RetryService`
- `repository/standup/StandupRepository` (stager), `templates/` (`SlackTemplateBuilder`, `ButtonType`,
  `dto/LayoutBlocks`)

### External
Slack Java SDK (`slack-api-client`: `Slack`, `RequestFormBuilder`, `GsonFactory`, chat responses;
`slack-app-backend`: `BlockActionPayload`, `ViewSubmissionPayload`, `ActionResponse`), OkHttp, Gson,
Spring Kafka `KafkaTemplate`, Spring `ApplicationEventPublisher` / `@EventListener`, Spring Web
`RestClient`, `kotlin-logging`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
