<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-09-22 -->

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
| `PayloadParseException.kt` | `enum class PayloadParseErrorCode : ErrorCode` — `APP_ID_NOT_FOUND` (400, "Application ID not found in payload.") and `UNSUPPORTED_SLACK_COMMAND_TYPE` (400, "Unsupported Slack command type in payload."). `AppIdNotFoundException(errorCode, details)` and `UnsupportedSlackCommandTypeException(rawCommandType: String, errorCode, details)`, both `: CodeCompanionRuntimeException` |
| `ControllerAdvice.kt` | `@RestControllerAdvice class ControllerAdvice`. `handleDatabaseException` logs `ERROR` (with the table name) and returns `500` `{"error": "internal_error"}`; `handleUnsupportedSlackCommandType` logs `WARN` and returns `400` with body `{"error": "Unsupported Slack command type: <rawCommandType>"}`; `handleUnexpected(e: Exception)` logs `ERROR` and returns the same `500` body |

## For AI Agents

### Working In This Directory
- Both exceptions are thrown only from `service/mention/SlackMentionEventHandlerImpl.parseAppMentionEvent`:
  `resolveAppId` throws `AppIdNotFoundException` when `api_app_id` is missing; `resolveCommandType`
  throws `UnsupportedSlackCommandTypeException` when `SlackEventType.valueOf(rawType.uppercase())` fails
  on the callback's top-level `type`. Details are built with the
  `exceptionDetails { "field" value "..." because "..." }` DSL from `domain/common/error/Errors.kt` —
  use it rather than hand-building `ExceptionArgument` lists.
- `AppIdNotFoundException` has no dedicated handler; it lands in `handleUnexpected` and surfaces as a
  logged 500. `ErrorCode` carries no HTTP status; the handler decides it. `CodeCompanionRuntimeException`
  exposes `errorCode` as a property, so a generic status-mapping handler can switch on it.
- `handleDatabaseException` covers `DatabaseException` thrown by `MeetingRepositoryImpl` and
  `StandupRepositoryImpl` via `schemaNotFound { }` / `throwIfSchemaNotFound`. It answers 500 on purpose:
  Slack retries a 5xx, and the request's transaction has already rolled back. Note that the retry is
  **not** deduplicated by idempotency key — `IdempotencyCreator` folds a one-second time window into the
  key, so a retry seconds later gets a fresh key; safety rests on the rollback alone. Never return 200
  from an error handler — that is how a DB failure once became invisible.
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
`ControllerAdviceTest` calls the three handlers directly and asserts status plus body; this module starts
no Spring context, so a `@WebMvcTest` slice does not belong here.
Build event payloads with `createAppMentionPayload(appId = null)` / `createAppMentionPayload(type = ...)`
from `src/testFixtures/kotlin/dev/notypie/application/service/mention/AppMentionPayloadCreator.kt`.

### Common Patterns
- `enum class XxxErrorCode(override val message: String) : ErrorCode` per failure family, mirrored by `JpaErrorCode` in infrastructure.
- Exceptions are plain `class`es with constructor-injected `errorCode` + `details`; extra context is a
  `val` property (`rawCommandType`) so handlers can log it.
- `@ExceptionHandler(value = [X::class])` returning `ResponseEntity<Map<String, String>>` with a single
  `"error"` key; log at `WARN` with the raw offending value for client errors, `ERROR` with the exception
  for server-side failures.
- English-only messages and identifiers; Kotlin named parameters when constructing exceptions.

## Dependencies

### Internal
- `domain/common/error/Errors.kt` — `CodeCompanionRuntimeException`, `ErrorCode`, `ExceptionArgument`,
  `exceptionDetails { }`
- `infrastructure/exception/meeting/DatabaseException` — handled (as a no-op) by `ControllerAdvice`
- `application/service/mention/SlackMentionEventHandlerImpl` — sole thrower of both exceptions

### External
Spring Web (`@RestControllerAdvice`, `@ExceptionHandler`, `ResponseEntity`, `HttpStatus`), kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
