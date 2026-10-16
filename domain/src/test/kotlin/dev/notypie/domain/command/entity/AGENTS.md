<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-15 -->

# domain/command/entity (test)

## Purpose
Specs for the `Command` aggregate and its concrete subclasses — the layer above contexts that owns the
`IntentQueue`, dispatches to `parseContext`, and catches `Exception` around execution (an `InterruptedException` is
rethrown after the interrupt flag is restored; an `Error` propagates).

## Key Files
All specs are Kotest `BehaviorSpec`s.

| File | Description |
|------|-------------|
| `CommandTest.kt` | Abstract `Command<NoSubCommands>` via anonymous subclasses. Mention payload → result comes from `context.runCommand()` (an `EmptyContext` yields `ok = false`); interaction payload with a `ReactionContext` → success through `handleInteraction`; interaction payload with a non-reactive context → `FAILED` / `ERROR_RESPONSE`; `parseContext` throwing → caught, `FAILED`, exception message in `errorReason`. Slash failure replies, through a file-private `ProbeCommand` (`/probe`, `ProbeSubCommand` `NONE` / `RUN` requiring one argument / `BARE` requiring one argument with a blank usage): every reply is asserted to carry its `directFallbackOf` copy to `TEST_USER_ID`; an unknown 72-character token with `<!channel>&` and a backtick → one requester-only ephemeral in `TEST_CHANNEL_ID` reading ``Unknown subcommand `&lt;!channel&gt;&amp;'xxx…`.`` (clipped to 40) plus `Usage:` and both usage lines, escaped; a token whose emoji (one surrogate pair) straddles the 40-character clip → the clip keeps 39 characters plus `…`, never half of the pair; `run` with no argument → ``Usage: `/probe run &lt;job&gt;` ``; `bare` with no argument → `Invalid arguments.` plus `Usage:` and both usage lines; a context throwing → ``Something went wrong handling `/probe`. Please try again.`` with `errorReason` = the exception's `toString()`; a nameless `TestCommand(failure = …)` on a slash payload → "that command"; the same on a mention payload → no effect staged. Uses `createMentionInboundCommand`, `createSlashInboundCommand`, `createInboundInteraction`, `createInteractionResponseInboundCommand`, `approveAction`, `TestCommand` |
| `InteractionCommandTest.kt` | `InteractionCommand(appName, idempotencyKey, commandData, actorRole)`. Mention `notice hello` as `ADMIN` succeeds; `APPROVAL_CALLBACK` interaction succeeds (reactive context); `APPROVAL_REQUEST` interaction fails because its `EmptyContext` is not a `ReactionContext`; `findSubCommandDefinition` → `MeetingSubCommandDefinition.NONE` for `MEETING_CREATE_REQUEST`, `NoSubCommands` for a mention |
| `ReplaceTextResponseCommandTest.kt` | `ReplaceTextResponseCommand(markdownMessage, replyHandle)`: `handleEvent` succeeds, definition is `NoSubCommands` |
| `RequestMeetingCommandTest.kt` | `slash.RequestMeetingCommand`. `findSubCommandDefinition`: none → `NONE`, `list` → `LIST`, unknown or `calendar` (removed 2026-10-08) → `SubCommandParseException`, and through `handleEvent` → `ERROR_RESPONSE` plus one ephemeral ``Unknown subcommand `<token>`.`` with `Usage:` and the `/meetup` and `/meetup list [...]` lines; `list today` → `MeetingListRequest` spanning exactly one day from start-of-day for `TEST_USER_ID`; `list bogus` → `ok = false` plus an `Ephemeral` containing "Unknown range 'bogus'"; an unknown range is echoed through `asEchoedToken()`: `<!channel>` → `Unknown range '&lt;!channel&gt;'. Usage: /meetup list [today \| tomorrow \| week \| month]`, a 60-character token → 40 characters plus `…`; no sub-commands → success. Uses `createSlashInboundCommand(subCommands = ...)` and `drainIntents()` |
| `CalendarCommandTest.kt` | `slash.CalendarCommand` (`/calendar`), driven through a local `runCalendar(vararg tokens)` helper: `CALENDAR_USAGE` is `/calendar connect \| disconnect \| status`; `connect` → success typed `CALENDAR_CONNECTION` with `CalendarConnect(TEST_USER_ID)`; `DISCONNECT` → `CalendarDisconnect`; `"", "status", " "` → blanks ignored, `CalendarStatus`; no action, `conect` (typo) and `connect now` → `ok = false` and one requester-only (`recipient = null`) ephemeral with its `directFallbackOf` copy: `Usage: …`, `Unknown action 'conect'. Usage: …`, `Usage: …`; an unknown action is echoed escaped and clipped: `<!channel>` → `Unknown action '&lt;!channel&gt;'. Usage: …`, and a 72-character token with `<!channel>&` and a backtick → `'&lt;!channel&gt;&amp;'xxx…'` (40 characters plus `…`) |
| `StandupCommandTest.kt` | `slash.StandupCommand`. `findSubCommandDefinition`: none → `NONE`, `setup` / `list` / `stop` → their entries, unknown → `SubCommandParseException` (through `handleEvent`: `ERROR_RESPONSE` plus ``Unknown subcommand `unknown_sub`.`` and the three usage lines, `&lt;routine-name&gt;` escaped); through `handleEvent`: a bare `stop` passes sub-command validation and yields `ok = false` plus the `STOP_USAGE_MESSAGE` `Ephemeral` (the guard against gating the name with `requiresArguments`, which would drop the reply), `stop daily sync` → `StopStandupRoutine("daily sync")`, `list` → `ListStandupRoutines` only; `answer` resolves to `ANSWER`, a preloaded candidate reaches the context and opens its modal on the slash trigger, and a command built without candidates tells the requester nothing is collecting |
| `AskCommandTest.kt` | `slash.AskCommand` over `createSlashInboundCommand`: `ASK_USAGE`; an AI-capable role posts one unthreaded `ChannelMessage` with `AgentQuestion`, detail type `AGENT_ASK_QUESTION` and the `channelRefusedNotice` fallback (inner spacing kept, outer blanks dropped, line breaks kept, `ADMIN` allowed); a role without `AI` → requester-only denial, success, nothing posted, and the denial wins over the usage hint; a blank question from an AI role → failed with the escaped usage ephemeral to the requester; the denial and usage ephemerals carry their `directFallbackOf` copy |

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
