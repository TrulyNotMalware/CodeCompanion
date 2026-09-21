<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-08-26 -->

# application/exception

## Purpose
Application-layer exception types plus the `@RestControllerAdvice` that turns them into HTTP responses.
The exceptions extend the domain base `CodeCompanionRuntimeException` and carry structured metadata via
an `ErrorCode` enum and `List<ExceptionArgument>` details. Coverage is deliberately narrow: two
payload-parse failures raised while mapping an `app_mention` event, and one infrastructure exception
(`DatabaseException`) that the advice currently swallows.

## Key Files
| File | Description |
|------|-------------|
| `PayloadParseException.kt` | `enum class PayloadParseErrorCode : ErrorCode` — `APP_ID_NOT_FOUND` (400, "Application ID not found in payload.") and `UNSUPPORTED_SLACK_COMMAND_TYPE` (400, "Unsupported Slack command type in payload."). `AppIdNotFoundException(errorCode, details)` and `UnsupportedSlackCommandTypeException(rawCommandType: String, errorCode, details)`, both `: CodeCompanionRuntimeException` |
| `ControllerAdvice.kt` | `@RestControllerAdvice class ControllerAdvice`. `handleDatabaseException(e: DatabaseException)` is a `Unit`-returning no-op, so Spring marks the request handled and the client receives an empty 200. `handleUnsupportedSlackCommandType` logs `WARN` and returns `400` with body `{"error": "Unsupported Slack command type: <rawCommandType>"}` |

## For AI Agents

### Working In This Directory
- Both exceptions are thrown only from `service/mention/SlackMentionEventHandlerImpl.parseAppMentionEvent`:
  `resolveAppId` throws `AppIdNotFoundException` when `api_app_id` is missing; `resolveCommandType`
  throws `UnsupportedSlackCommandTypeException` when `SlackEventType.valueOf(rawType.uppercase())` fails
  on the callback's top-level `type`. Details are built with the
  `exceptionDetails { "field" value "..." because "..." }` DSL from `domain/common/error/Errors.kt` —
  use it rather than hand-building `ExceptionArgument` lists.
- `AppIdNotFoundException` has **no** handler in `ControllerAdvice`; it falls through to Spring's
  default handling and surfaces as a 500. `PayloadParseErrorCode.statusCode` (400) is metadata only —
  nothing reads it when building a response. `CodeCompanionRuntimeException` does not expose `errorCode`
  as a property (only `details` and the inherited `message`), so a generic status-mapping handler would
  need that added in `domain` first.
- `handleDatabaseException` hides `DatabaseException` (thrown by `MeetingRepositoryImpl` and
  `StandupRepositoryImpl` via `schemaNotFound { }` / `throwIfSchemaNotFound`) behind an empty 200. Give
  it a status and body before relying on error surfaces in production.
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
There is no spec for `ControllerAdvice`; when adding one, use a `@WebMvcTest` slice against
`SlackEventController` with a MockK `AppMentionEventHandler` that throws, and assert status plus body.
Build event payloads with `createAppMentionPayload(appId = null)` / `createAppMentionPayload(type = ...)`
from `src/testFixtures/kotlin/dev/notypie/application/service/mention/AppMentionPayloadCreator.kt`.

### Common Patterns
- `enum class XxxErrorCode(override val statusCode: Int, override val message: String) : ErrorCode` per
  failure family, mirrored by `JpaErrorCode` in infrastructure.
- Exceptions are plain `class`es with constructor-injected `errorCode` + `details`; extra context is a
  `val` property (`rawCommandType`) so handlers can log it.
- `@ExceptionHandler(value = [X::class])` returning `ResponseEntity<Map<String, String>>` with a single
  `"error"` key; log at `WARN` with the raw offending value.
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
