<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-10-11 -->

# application/controllers

## Purpose
HTTP entry points for inbound Slack traffic. Controllers parse the raw request, do the minimum routing,
and delegate to a service interface — no business logic lives here. `SlackEventController` serves the
Events API webhook and interactive-component callbacks; `SlashCommandController` serves the slash
commands. Both are component-scanned `@RestController`s. The same service interfaces are also driven by
`socket/SocketModeReceiver` when the app runs in Socket Mode, so these classes are the HTTP transport
only.

## Key Files
| File | Description |
|------|-------------|
| `SlackEventController.kt` | `@RequestMapping("/api/slack")`. `POST /events` (JSON body as `Map<String, Any>`, `produces` JSON): echoes only `{"challenge": ...}` for `url_verification`, ACKs every non-`app_mention` event with an empty 200, and calls `AppMentionEventHandler.handleEvent(headers, payload)` for an `app_mention`, also answering an empty 200 (a failed command is logged at `WARN` with the control characters of its `errorReason` replaced by `?` through `stripControlCharacters`, a mention with nothing to do — `Status.DO_NOTHING`, e.g. the bot's own or a workflow's mention the handler ignores — only at `DEBUG`; its `errorReason`, which can hold `exception.toString()`, never goes into the response — 2026-10-02). Returns `ResponseEntity<Map<String, Any>>`. `POST /interaction`: takes the form param `payload` (JSON string) and calls `InteractionHandler.handleInteraction(headers, payload): String?`; a non-null ack is returned as `application/json`, null becomes an empty-string 200 (`ResponseEntity<String>`) |
| `SlashCommandController.kt` | `@RequestMapping("/api/slash")`. Each service call runs inside `ViewOpenDeferral.afterBoundary { … }`, so a modal the command stages is opened only after the service's `@Transactional` method returned and released its connection. Every mapping declares `consumes = APPLICATION_FORM_URLENCODED_VALUE` (the request Slack sends; it was `produces` until 2026-10-02, which matched on the `Accept` header instead), takes `@RequestParam data: Map<String, String>`, parses via `parseRequestBodyData(headers, data)` and returns `Unit` (empty 200). `POST /meet` → `MeetingService.handleMeeting`; `/standup` → `StandupSlashService.handleStandup`; `/calendar` → `CalendarSlashService.handleCalendar` (the `/calendar` command, 2026-10-08); `/subscribe`, `/unsubscribe`, `/subscriptions` → `CveSubscriptionSlashService.handleSubscribe` / `handleUnsubscribe` / `handleSubscriptions`; `/latest` → `CveQuerySlashService.handleLatest` |
| `GoogleOAuthCallbackController.kt` | `@RestController @Conditional(OnGoogleCalendarEnabled)`, depends on the `CalendarConnectionCallback` interface. `GET /oauth/google/callback?code&state[&error]` (`CALLBACK_PATH`): the browser lands here after Google consent; outside the Slack signature filter (`shouldNotFilter` keeps to Slack paths). Renders a fixed-text HTML page per `CalendarConnectionOutcome` — `CONNECTED` 200, `DENIED` 200, `SCOPE_DENIED` 200 (calendar scope unticked; retry and allow it), `INVALID_STATE` 400, `EXCHANGE_FAILED` 502, `STORE_FAILED` 503 (the connection could not be saved after one retry; the state is already spent, so the page says the link cannot be used again and points at `/calendar connect`) — with `Cache-Control: no-store`; no request parameter is ever echoed into the page. Any other exception from `completeConnection` (a database failure while consuming the state or storing the connection, a non-conflict `DataAccessException`, an `InterruptedException` from the scope-denied revoke) lands in the controller's own `@ExceptionHandler(Exception)` `handleUnexpected`: ERROR with the exception, the interrupt flag restored for an `InterruptedException`, and the same page style as 500 with `no-store` ("The connection could not be completed"; the link may be spent, so it points at `/calendar connect` for a new one). Spring prefers a controller-local handler over `ControllerAdvice` for this controller only, so the browser never gets the Slack JSON `{"error": "internal_error"}` and the Slack controllers' error handling is unchanged |
| `dto/CodeCompanionResponse.kt` | `data class CodeCompanionResponse(ok: Boolean = true, message: String)` — no current call sites |
| `dto/ResponseDto.kt` | `EventResponseDto(message, event: Event, isAccepted)` and `Event(eventId: UUID, type: CommandDetailType, acceptedTime: Long)` — no current call sites |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `dto/` | Controller-layer response classes. Neither is referenced by a controller today; `handleAppMentionEvents` answers with an empty body (see `dto/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Keep controllers thin: parse → delegate → return. Route on transport facts (event type, path) only,
  never on business state.
- `handleAppMentionEvents` returns `200 OK` for every non-`app_mention` event on purpose. Slack fans
  many event types into this one webhook and retries on non-2xx; some subtypes (e.g. `message_deleted`)
  lack `event.user`, which would break the strict non-nullable deserialization downstream. The type is
  read by `extractEventType` from `payload["event"]["type"]`; the challenge check compares
  `payload["type"]` to `SlackEventType.URL_VERIFICATION` lowercased.
- `/api/slack/interaction` receives a URL-encoded `payload` form field, not a JSON body. Do not add
  `@RequestBody`; the JSON inside is parsed by the infrastructure `InteractionPayloadParser`
  (`SlackInteractionRequestParser` bean) inside `SlackInteractionHandlerImpl`.
- A `view_submission` that needs an inline error is the only case where an interaction reply carries a
  body — `InteractionHandler` returns the `response_action` JSON and the controller must send it as
  `application/json`. Keep that branch; a plain empty 200 would drop the validation message.
- Adding a slash endpoint means three edits: a `@PostMapping` here, a `when` branch in
  `socket/SocketModeReceiver.dispatchSlash`, a command name in `AppConfig.Socket` (and, for `local`, a
  `slack.app.socket.<name>-command` line in `application-local.yaml`), plus a command in
  `docs/slack-app-manifest.yaml` — `/calendar` (2026-10-08) is the latest example. The Slack command
  name (`/meetup`, `AppConfig.Socket.meetingCommand`) is independent of the HTTP path
  (`/api/slash/meet`); the README documents the current mapping.
- Slack signature/timestamp verification and retry de-duplication run upstream in
  `security/SlackRequestVerificationFilter` for `/api/slash/`, `/api/slack/events` and
  `/api/slack/interaction`. A new controller path outside those prefixes is unauthenticated until it is
  added to `SLACK_REQUEST_PATHS`.
- The CVE slash services are always present as beans and no-op when `slack.app.cve.enabled` is false;
  the controller needs no feature check of its own.

### Testing Requirements
```bash
./gradlew :application:test
```
`test/.../controllers/SlackControllersTest` is a standalone `MockMvc` spec with mocked services: the response
contract (empty ack body, challenge-only echo, `consumes` selection, 415) and, for every slash endpoint, that
`views.open` waits until the service has returned (`ViewOpenDeferral.afterBoundary`). Use cases are covered by the
service specs (`SlackMentionEventHandlerImplTest`, `SlackInteractionHandlerImplTest`, `MeetingServiceImplTest`,
`CveSubscriptionSlashServiceImplTest`, `CveQuerySlashServiceImplTest`) and the filter specs under `security/`.
`GoogleOAuthCallbackControllerTest` covers each outcome page and the unexpected-exception 500 page on the same standalone
setup, which registers the real `ControllerAdvice`, so the HTML assertions pin the controller-local handler's precedence
over the advice's JSON 500.
When adding a controller case, use a `@WebMvcTest` slice or the same standalone setup with MockK-backed service interfaces
(`spring-boot-starter-test` and `spring-restdocs-mockmvc` are on the test classpath;
`src/testFixtures/kotlin/dev/notypie/docs/` holds the REST Docs DSL). Assert that a non-`app_mention`
event returns 200 without calling `AppMentionEventHandler`, that a `url_verification` body is echoed,
and that a non-null interaction ack is served as JSON. Build `InboundCommand` inputs with the domain
testFixtures creators (`createSlashInboundCommand` etc.) rather than inline.

### Common Patterns
- Constructor-injected service interfaces (`MeetingService`, `StandupSlashService`, ...), never the
  `Impl` types.
- `@RequestHeader headers: MultiValueMap<String, String>` is threaded through every handler even where
  unused, so services keep one signature across HTTP and Socket Mode.
- Kotlin named parameters at every call
  (`meetingService.handleMeeting(headers = ..., payload = ..., commandData = ...)`).
- File-level `private val logger = KotlinLogging.logger {}` where logging is needed.

## Dependencies

### Internal
- `application/common/SlackRequestParser` — `parseRequestBodyData(headers, data)`
- `application/service/mention/AppMentionEventHandler` — `app_mention` events
- `application/service/interaction/InteractionHandler` — interactive-component payloads
- `application/service/meeting/MeetingService`, `application/service/standup/StandupSlashService`,
  `application/service/calendar/CalendarSlashService` — `/calendar`
- `application/service/cve/subscription/CveSubscriptionSlashService` — `/subscribe`, `/unsubscribe`,
  `/subscriptions`
- `application/service/cve/query/CveQuerySlashService` — `/latest`
- `infrastructure/impl/command/slack/SlackEventType` — `URL_VERIFICATION` challenge match
- `domain/command/entity/CommandDetailType` — referenced by `dto/ResponseDto.kt`

### External
Spring Web MVC (`@RestController`, `@PostMapping`, `ResponseEntity`, `MediaType`, `MultiValueMap`),
kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
