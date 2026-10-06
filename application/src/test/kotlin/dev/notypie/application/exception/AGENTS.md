<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-22 | Updated: 2026-10-02 -->

# application/src/test/kotlin/dev/notypie/application/exception

## Purpose
Specs for the `@RestControllerAdvice` in `src/main/kotlin/dev/notypie/application/exception`. No Spring
context: the handlers are called directly and their `ResponseEntity` status/body asserted, and handler
selection is proven with `ExceptionHandlerMethodResolver(ControllerAdvice::class.java)`.

## Key Files
| File | Description |
|------|-------------|
| `ControllerAdviceTest.kt` | `DatabaseException` → 500 `{"error": "internal_error"}`; unexpected `Exception` → same 500; `UnsupportedSlackCommandTypeException` → 400 `{"error": "unsupported_command_type"}` (the raw type is not echoed) with `X-Slack-No-Retry: 1`; a Spring MVC `NoResourceFoundException` fed through the inherited `handleException(ex, WebRequest)` stays 404; the resolver picks `handleException` for `NoResourceFoundException`, `handleUnexpected` for `IllegalStateException`, and `handleUnsupportedSlackCommandType` for its exception; `AppIdNotFoundException` and `InvalidEventPayloadException` → 400 `invalid_payload` with `X-Slack-No-Retry: 1`, and the resolver picks `handleUnreadablePayload` over the catch-all |

## For AI Agents

### Working In This Directory
- The resolver cases are the regression guard for the catch-all: `ControllerAdvice` extends
  `ResponseEntityExceptionHandler` precisely so framework exceptions keep their status. If that inheritance
  is removed, or a new handler makes the mapping ambiguous, these specs fail before production does.
- `ServletWebRequest(MockHttpServletRequest(...))` from `spring-test` is enough for the inherited handler;
  do not introduce a `@WebMvcTest` slice — this module starts no Spring context.

### Testing Requirements
```bash
./gradlew :application:test --tests '*ControllerAdviceTest*'
```

## Dependencies

### Internal
- `dev.notypie.exception.meeting.DatabaseException` / `JpaErrorCode` from `:infrastructure`
- `PayloadParseErrorCode` from the main package, `SlackHeaders` from `application/security`

### External
- `spring-test` (`MockHttpServletRequest`), `spring-webmvc` (`NoResourceFoundException`), `spring-web` (`ExceptionHandlerMethodResolver`)
