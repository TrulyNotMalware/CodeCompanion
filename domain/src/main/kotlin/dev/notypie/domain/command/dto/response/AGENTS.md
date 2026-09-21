<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/dto/response

## Purpose
The value `Command.handleEvent()` returns: metadata about how the run went (`ok`, `status`, routing
type, error reason). It is deliberately not the list of effects — those come from `drainIntents()`.

## Key Files
| File | Description |
|------|-------------|
| `CommandOutput.kt` | `open class CommandOutput(ok, apiAppId, status, commandDetailType, idempotencyKey, publisherId, channel, token = "", commandType, errorReason = "", messageTs = "")`; factories `empty()` (`ok = false`, `DO_NOTHING`, random key), `fail(basicInfo, commandDetailType, reason, commandType = SIMPLE)`, `success(basicInfo, commandType, commandDetailType)` |
| `Status.kt` | `IN_PROGRESSED`, `SUCCESS`, `FAILED`, `DO_NOTHING`; `Status.isOk(status)` is true for the first two |

## For AI Agents

### Working In This Directory
- A `fail(...)` output does not cancel queued effects. `CommandContext.createErrorResponse` queues an
  error ephemeral *and* returns `fail`; `application/service/command/CommandExecutor.kt` drains and
  delivers regardless of `ok`. Never gate `drainIntents()` on `ok`.
- `open` exists for exactly one subclass, `entity/slash/RequestMeetingContextResult` (adds `meeting`),
  which `application/service/meeting/MeetingServiceImpl.createNewMeeting` consumes. Note that
  `RequestMeetingContext.interactionResults` hard-codes `ok = true` even for `status = FAILED`, so
  consumers of that subclass must read `status`.
- `empty()` is what `EmptyContext` and every no-op path return: `ok = false` with `DO_NOTHING`, and
  `Status.isOk(DO_NOTHING) == false`. Treat "not ok" as "nothing to do" unless `status == FAILED`.
- `token` and `messageTs` are placeholders (the source carries a `FIXME`); `token` is only populated by
  `RequestMeetingContextResult` and `ApprovalCallbackContext`. Do not add logic that depends on them.
- `IN_PROGRESSED` is produced by no context; it only participates in `ApprovalCallbackContext`'s
  status aggregation. `Command.handleEvent()`'s catch-all uses `fail(..., ERROR_RESPONSE, ...)`.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.entity.CommandTest'
```
Every context spec asserts `output.ok` / `output.status` / `output.commandDetailType`; there is no
spec for `Status.isOk` on its own.

### Common Patterns
- Contexts return `CommandOutput.success(basicInfo = commandBasicInfo, commandType = commandType,
  commandDetailType = commandDetailType)` — copy the three-argument form rather than the constructor.

## Dependencies

### Internal
- `command/dto/CommandBasicInfo`, `command/entity/CommandType`, `command/entity/CommandDetailType`

### External
`java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
