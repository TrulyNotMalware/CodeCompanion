<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-22 | Updated: 2026-09-22 -->

# application/src/test/kotlin/dev/notypie/application/exception

## Purpose
Specs for the `@RestControllerAdvice` in `src/main/kotlin/dev/notypie/application/exception`. No Spring
context: the handlers are called directly and their `ResponseEntity` status/body asserted.

## Key Files
| File | Description |
|------|-------------|
| `ControllerAdviceTest.kt` | `DatabaseException` → 500 `{"error": "internal_error"}`; unexpected `Exception` → same 500; `UnsupportedSlackCommandTypeException` → 400 naming the type; a Spring MVC `NoResourceFoundException` fed through the inherited `handleException(ex, WebRequest)` stays 404 |

## For AI Agents

### Working In This Directory
- The 404 case is the regression guard for the catch-all: `ControllerAdvice` extends
  `ResponseEntityExceptionHandler` precisely so framework exceptions keep their status. If that inheritance
  is removed, this spec fails before production does.
- `ServletWebRequest(MockHttpServletRequest(...))` from `spring-test` is enough for the inherited handler;
  do not introduce a `@WebMvcTest` slice — this module starts no Spring context.

### Testing Requirements
```bash
./gradlew :application:test --tests '*ControllerAdviceTest*'
```

## Dependencies

### Internal
- `dev.notypie.exception.meeting.DatabaseException` / `JpaErrorCode` from `:infrastructure`
- `PayloadParseErrorCode` from the main package

### External
- `spring-test` (`MockHttpServletRequest`), `spring-webmvc` (`NoResourceFoundException`)
