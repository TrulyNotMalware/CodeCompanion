<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# domain/command/exceptions

## Purpose
The command pipeline's own error family, built on `common/error`: an `internal` `ErrorCode` enum and a
sealed exception hierarchy thrown while parsing sub-commands or routing an unsupported payload.

## Key Files
| File | Description |
|------|-------------|
| `CommandErrorCode.kt` | `internal enum CommandErrorCode : ErrorCode` — `SUBCOMMAND_NOT_VALID`, `SUBCOMMAND_NOT_FOUND`, `UNSUPPORTED_COMMAND_TYPE` (message only) |
| `CommandException.kt` | `internal sealed class CommandException : CodeCompanionRuntimeException`; `SubCommandParseException(commandName, subCommandName, errorCode, details)`; `UnSupportedCommandException(commandType, errorCode, details)` |

## For AI Agents

### Working In This Directory
- Throw sites: `entity/Command.createSubCommand` (`SUBCOMMAND_NOT_VALID`),
  `entity/slash/RequestMeetingCommand` and `SetupStandupCommand.findSubCommandDefinition`
  (`SUBCOMMAND_NOT_FOUND`), `entity/Command.executeInteraction` and
  `entity/InteractionCommand.buildParser` (`UNSUPPORTED_COMMAND_TYPE`). The never-thrown
  `COMMAND_NOT_FOUND` / `UNKNOWN_SUBCOMMAND_TYPE` / `VALIDATION_FAILED` were removed on 2026-09-22.
- Nothing here escapes the domain. `Command.handleEvent()` wraps execution in `runCatching` and turns
  any throwable into `CommandOutput.fail(..., ERROR_RESPONSE, reason = exception.toString())`. The user
  therefore sees the class name plus `errorCode.message`; `details` built with `exceptionDetails {}` are
  dropped at that boundary unless a context renders them first.
- Everything is `internal`, so `:application` cannot catch these types — it only observes
  `CommandOutput.ok == false`. Do not widen visibility to special-case them upstream; add a
  `CommandDetailType` or an outbound message instead.
- `CommandErrorCode.VALIDATION_FAILED` duplicates `CommonErrorCode.VALIDATION_FAILED`; entity validation
  goes through `validate {}` and never uses this one.
- The thrown exception keeps `errorCode` as a property; there is no HTTP status on domain codes.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.entity.*'
```
`CommandTest`, `RequestMeetingCommandTest` and `InteractionCommandTest` exercise the throw sites and
assert the resulting `ERROR_RESPONSE` output. `UnknownSubCommandDefinition` in `testFixtures` is the
fixture for the invalid-sub-command path.

### Common Patterns
- `exceptionDetails { "field" value actual because "reason" }` at every throw site; never a bare
  `ExceptionArgument(...)`.
- Subclasses expose the offending token as a named property (`commandName`, `subCommandName`,
  `commandType`) in addition to `details`.

## Dependencies

### Internal
- `common/error` — `ErrorCode`, `CodeCompanionRuntimeException`, `ExceptionArgument`

### External
None beyond the Kotlin stdlib.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
