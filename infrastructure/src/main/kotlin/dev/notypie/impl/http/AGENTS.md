<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-15 | Updated: 2026-10-26 -->

# infrastructure/impl/http

## Purpose
Transport helpers shared by the adapters that call third-party HTTP APIs with the JDK `HttpClient`: one deadline
over a response's headers and body, a body size cap, and the `Retry-After` header parser. Moved here from
`impl/cve/SourceAdapter.kt` on 2026-10-15 so the calendar clients no longer depend on CVE internals. Everything is
`internal` to the `infrastructure` module; nothing here is a Spring bean.

## Key Files
| File | Description |
|------|-------------|
| `BoundedHttp.kt` | `BoundedResponse(statusCode, headers, body)` (the headers stay available to callers such as the GitHub adapter's rate-limit log); `BodyTooLargeException` / `BodyTimeoutException` (both `IOException`s); `HttpClient.sendWithinDeadline(request, deadline, maxBodyBytes)`, which sends with `BodyHandlers.ofInputStream()`, schedules a watchdog on one shared daemon thread `http-body-watchdog` that closes the body stream when `deadline` (measured from the send) runs out, reads at most `maxBodyBytes + 1` bytes and throws `BodyTooLargeException` past the cap or `BodyTimeoutException` when the watchdog closed the stream; an `IOException` from the read on an interrupted thread, or one whose cause is an `InterruptedException`, becomes an `InterruptedException` (cause: that `IOException`) with the flag set, checked before the timeout (2026-10-26; `@Throws(IOException, InterruptedException)`); `parseRetryAfter(value): Duration?` reads seconds (any size, clamped to `[0, MAX_RETRY_AFTER]`) or an RFC 1123 HTTP date (the time left until it, clamped the same way, so a past date is zero) and returns `null` for a missing or unreadable value; `MAX_RETRY_AFTER` = 24 h |

## For AI Agents

### Working In This Directory
- **`HttpRequest.timeout()` bounds only the wait for the response headers.** A server that sends headers and then
  stalls the body would hold the calling thread without the watchdog; callers pass the same `requestTimeout` as
  `deadline`, so headers and body together stay within it.
- **Callers:** `impl/cve/NvdCveSourceAdapter` and `impl/cve/GithubReleaseSourceAdapter` (`sendWithinDeadline`),
  `impl/calendar/GoogleOAuthClient` (`sendWithinDeadline`), `impl/calendar/GoogleCalendarClient` (both helpers),
  and `impl/command/ApplicationMessageDispatcher` (`parseRetryAfter` for Slack's 429s, on OkHttp). Keep one parser:
  the calendar client accepted only integer seconds until 2026-10-15.
- `parseRetryAfter` reads the wall clock (`Instant.now()`) for an HTTP date; the 24 h clamp is also the outbox's
  give-up bound, so a huge value cannot overflow the relay's `LocalDateTime` arithmetic (`impl/command/AGENTS.md`).
  The zero clamp matters as much: the dispatcher passes a short hint to `Thread.sleep`, which throws on a negative
  value. Only the IMF-fixdate (RFC 1123) form is read; the obsolete RFC 850 and asctime forms that RFC 9110 also
  asks recipients to accept come back as `null` (no hint), which both callers survive.
- The exception class names are visible outside the logs: `GoogleCalendarClient` turns a transport failure into `Failed(message = "request failed: <simple class name>")`, which the sync worker stores in `meeting_calendar_event.last_error` and puts in the final failure DM. The 2026-10-15 rename therefore changed that text (`SourceBodyTimeoutException` → `BodyTimeoutException`, `SourceBodyTooLargeException` → `BodyTooLargeException`); nothing parses it. Renaming them again changes it again.
- Both body exceptions are `IOException`s, like the send's own transport failures: every `sendWithinDeadline`
  caller catches them together (an empty list, `Failed`, `false` or `GoogleOAuthException`) and rethrows only
  `InterruptedException`.
- **An interrupt during the body read must leave as `InterruptedException`.** The JDK's `ofInputStream` body sets
  the flag and wraps the `InterruptedException` from its buffer queue in an `IOException`; rethrown as is, every
  caller above counted it as a transport failure (a revoke Google had already answered 200 became `false`, a
  calendar sync spent an attempt). The conversion here is what lets their `catch (InterruptedException)` /
  `runCatchingExceptions` see it.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.http.*' --tests 'dev.notypie.impl.cve.*' --tests 'dev.notypie.impl.calendar.*' --tests 'dev.notypie.impl.command.ApplicationMessageDispatcherTest'
```
`test/.../impl/http/BoundedHttpTest` pins `parseRetryAfter` directly: the clamps at zero (negative seconds, a past
date) and at `MAX_RETRY_AFTER`, whitespace, and the inputs that give no hint; it also pins the interrupt conversion
of `sendWithinDeadline` over a MockK `HttpClient` whose body stream throws, and `GoogleOAuthClientTest` interrupts a
real revoke parked in the body read. The CVE adapter specs drive
`sendWithinDeadline` against a loopback server that stalls after the headers (the watchdog fires) and one that sends
more than `maxBodyBytes`; `GoogleCalendarClientTest` and `ApplicationMessageDispatcherTest` pin the parser through
their clients.

## Dependencies

### External
JDK `java.net.http`, `java.util.concurrent`, `java.time`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
