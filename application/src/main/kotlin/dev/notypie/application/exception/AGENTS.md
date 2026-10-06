<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-10-02 -->

# application/exception

## Purpose
Application-layer exception types plus the `@RestControllerAdvice` that turns them into HTTP responses.
The exceptions extend the domain base `CodeCompanionRuntimeException` and carry structured metadata via
an `ErrorCode` enum and `List<ExceptionArgument>` details. Coverage is deliberately narrow: two
payload-parse failures raised while mapping an `app_mention` event, one infrastructure exception
(`DatabaseException`), and a catch-all for anything else.

## Key Files
| File | Description |
|------|-------------|
| `PayloadParseException.kt` | `enum class PayloadParseErrorCode : ErrorCode` — `APP_ID_NOT_FOUND` ("Application ID not found in payload.") and `UNSUPPORTED_SLACK_COMMAND_TYPE` ("Unsupported Slack command type in payload."). `AppIdNotFoundException(errorCode, details)` and `UnsupportedSlackCommandTypeException(rawCommandType: String, errorCode, details)`, both `: CodeCompanionRuntimeException` |
| `ControllerAdvice.kt` | `@RestControllerAdvice class ControllerAdvice`. `handleDatabaseException` logs `ERROR` (with the table name) and returns `500` `{"error": "internal_error"}`; `handleUnsupportedSlackCommandType` logs `WARN` (raw type with control characters replaced by `?`) and returns `400` `{"error": "unsupported_command_type"}` with `X-Slack-No-Retry: 1` — the user-controlled type is never echoed in the body; `handleUnexpected(e: Exception)` logs `ERROR` and returns the same `500` body; `handleUnreadablePayload` answers `400` `{"error": "invalid_payload"}` with `X-Slack-No-Retry: 1` for `AppIdNotFoundException` and `InvalidEventPayloadException` |

## For AI Agents

### Working In This Directory
- Both exceptions are thrown only from `service/mention/SlackMentionEventHandlerImpl.parseAppMentionEvent`:
  `resolveAppId` throws `AppIdNotFoundException` when `api_app_id` is missing; `resolveCommandType`
  throws `UnsupportedSlackCommandTypeException` when `SlackEventType.valueOf(rawType.uppercase())` fails
  on the callback's top-level `type`. Details are built with the
  `exceptionDetails { "field" value "..." because "..." }` DSL from `domain/common/error/Errors.kt` —
  use it rather than hand-building `ExceptionArgument` lists.
- **Unreadable payloads are 400 with `X-Slack-No-Retry: 1`** (2026-10-02): `handleUnreadablePayload` takes
  `AppIdNotFoundException` and `InvalidEventPayloadException` (the mention handler wraps a failed
  `SlackEventCallBackRequest` binding in it) and logs at `WARN`. They used to fall into `handleUnexpected` as 500, and
  Slack resent the same payload three times. Whether Slack really sends such payloads was not observed.
- `ErrorCode` carries no HTTP status; the handler decides it. `CodeCompanionRuntimeException` exposes `errorCode` as a
  property, so a generic status-mapping handler can switch on it.
- `handleDatabaseException` covers `DatabaseException` thrown by `MeetingRepositoryImpl` and
  `StandupRepositoryImpl` via `schemaNotFound { }` / `throwIfSchemaNotFound`. It answers 500 on purpose:
  Slack retries a 5xx, and the request's transaction has already rolled back. Note that the retry is
  **not** deduplicated by idempotency key — `IdempotencyCreator` folds a one-second time window into the
  key, so a retry seconds later gets a fresh key. The signature filter defers (503) any Slack retry that
  arrives while the original is still running and forgets an attempt that ended in 5xx, so the retry after
  this 500 is processed on the same replica (see `security/AGENTS.md` for the cross-replica limits). Never
  return 200 from an error handler — that is how a DB failure once became invisible.
- `X-Slack-No-Retry: 1` goes only on deterministic client errors (today: the unsupported-type 400, raised
  only on `/api/slack/events`). Slack still counts the response as a failure but stops retrying a payload
  that can never succeed. Never put it on a 5xx: those must be retried.
- The advice extends `ResponseEntityExceptionHandler`, so Spring MVC's own exceptions (`NoResourceFoundException`
  → 404, `HttpMessageNotReadableException` → 400, `HttpRequestMethodNotSupportedException` → 405, ...) keep
  their status via the inherited `handleException`; only exceptions outside that list reach `handleUnexpected`.
- Over Socket Mode (`socket/SocketModeReceiver`) there is no MVC dispatch, so nothing here applies —
  failures are caught with `runCatching` and logged in the receiver.
- Keep infrastructure exceptions (JPA, Kafka, Slack SDK) in `infrastructure`; only failures of
  application orchestration belong in this package. New types: extend `CodeCompanionRuntimeException`,
  add an `ErrorCode` enum value, and register an `@ExceptionHandler` here in the same change.

### Testing Requirements
```bash
./gradlew :application:test --tests '*SlackMentionEventHandlerImplTest*'
```
`SlackMentionEventHandlerImplTest` asserts that a payload without `api_app_id` throws
`AppIdNotFoundException` and an unknown event type throws `UnsupportedSlackCommandTypeException`.
`ControllerAdviceTest` calls the three handlers directly and asserts status, body and headers, and proves
handler selection with `ExceptionHandlerMethodResolver(ControllerAdvice::class.java).resolveMethod(...)`
(framework 404 → inherited `handleException`, anything else → `handleUnexpected`); this module starts no
Spring context, so a `@WebMvcTest` slice does not belong here.
Build event payloads with `createAppMentionPayload(appId = null)` / `createAppMentionPayload(type = ...)`
from `src/testFixtures/kotlin/dev/notypie/application/service/mention/AppMentionPayloadCreator.kt`.

### Common Patterns
- `enum class XxxErrorCode(override val message: String) : ErrorCode` per failure family, mirrored by `JpaErrorCode` in infrastructure.
- Exceptions are plain `class`es with constructor-injected `errorCode` + `details`; extra context is a
  `val` property (`rawCommandType`) so handlers can log it.
- `@ExceptionHandler(value = [X::class])` returning `ResponseEntity<Map<String, String>>` with a single
  `"error"` key holding a fixed code, never user input; log at `WARN` with the offending value (control
  characters stripped) for client errors, `ERROR` with the exception for server-side failures.
- English-only messages and identifiers; Kotlin named parameters when constructing exceptions.

## Dependencies

### Internal
- `domain/common/error/Errors.kt` — `CodeCompanionRuntimeException`, `ErrorCode`, `ExceptionArgument`,
  `exceptionDetails { }`
- `infrastructure/exception/meeting/DatabaseException` — answered 500 by `ControllerAdvice`
- `application/service/mention/SlackMentionEventHandlerImpl` — sole thrower of both exceptions
- `application/security/SlackHeaders` — `NO_RETRY` header name

### External
Spring Web (`@RestControllerAdvice`, `@ExceptionHandler`, `ResponseEntity`, `HttpStatus`), kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
