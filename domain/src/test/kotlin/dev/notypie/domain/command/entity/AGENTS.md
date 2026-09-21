<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# domain/command/entity (test)

## Purpose
Specs for the `Command` aggregate and its concrete subclasses — the layer above contexts that owns the
`IntentQueue`, dispatches to `parseContext`, and wraps execution in `runCatching`.

## Key Files
All specs are Kotest `BehaviorSpec`s.

| File | Description |
|------|-------------|
| `CommandTest.kt` | Abstract `Command<NoSubCommands>` via anonymous subclasses. Mention payload → result comes from `context.runCommand()` (an `EmptyContext` yields `ok = false`); interaction payload with a `ReactionContext` → success through `handleInteraction`; interaction payload with a non-reactive context → `FAILED` / `ERROR_RESPONSE`; `parseContext` throwing → caught, `FAILED`, exception message in `errorReason`. Uses `createMentionInboundCommand`, `createInboundInteraction`, `createInteractionResponseInboundCommand`, `approveAction` |
| `InteractionCommandTest.kt` | `InteractionCommand(appName, idempotencyKey, commandData, actorRole)`. Mention `notice hello` as `ADMIN` succeeds; `APPROVAL_CALLBACK` interaction succeeds (reactive context); `APPROVAL_REQUEST` interaction fails because `ApprovalFormContext` is not a `ReactionContext`; `findSubCommandDefinition` → `MeetingSubCommandDefinition.NONE` for `MEETING_CREATE_REQUEST`, `NoSubCommands` for a mention |
| `ReplaceTextResponseCommandTest.kt` | `ReplaceTextResponseCommand(markdownMessage, replyHandle)`: `handleEvent` succeeds, definition is `NoSubCommands` |
| `RequestMeetingCommandTest.kt` | `slash.RequestMeetingCommand`. `findSubCommandDefinition`: none → `NONE`, `list` → `LIST`, unknown → `SubCommandParseException`; `list today` → `MeetingListRequest` spanning exactly one day from start-of-day for `TEST_USER_ID`; `list bogus` → `ok = false` plus an `Ephemeral` containing "Unknown range 'bogus'"; no sub-commands → success. Uses `createSlashInboundCommand(subCommands = ...)` and `drainIntents()` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `slash/` | `MeetingListRangeTest` — the range enum behind `list <range>` (see `slash/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- `CommandTest` is the place to pin `Command.handleEvent` semantics (error wrapping, reactive vs
  non-reactive dispatch). Context behaviour belongs in `../context/`.
- Anonymous `object : Command<NoSubCommands>(...)` subclasses are the idiom for testing the abstract
  class; `TestCommand` in `testFixtures` exists for `:application`'s executor specs and is not used
  here.
- `RequestMeetingCommandTest` asserts against `LocalDateTime.now()`; it passes as long as the test does
  not straddle midnight.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.entity.*'
```

### Common Patterns
- `command.handleEvent()` then `command.drainIntents()`; assert `result.ok`, `result.status`,
  `result.commandDetailType`, then the drained effects.
- `shouldThrow<SubCommandParseException>` for unknown slash sub-commands.

## Dependencies

### Internal
- `dev.notypie.domain.command.entity.{Command, InteractionCommand, ReplaceTextResponseCommand, CommandType, CommandDetailType}`;
  `entity.context.{CommandContext, EmptyContext, ReactionContext}`; `entity.slash.{RequestMeetingCommand,
  MeetingSubCommandDefinition}`; `command.exceptions.SubCommandParseException`; `command.authorization.UserRole`.
- `testFixtures` — `command/InboundCommandCreator.kt`, `command/InboundInteractionInputCreator.kt`, `Constants.kt`.

### External
- Kotest (`BehaviorSpec`, `shouldThrow`, `shouldBe`, `shouldBeInstanceOf`), `java.time`, `java.util.UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
