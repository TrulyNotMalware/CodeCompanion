<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-11 -->

# domain/command/entity

## Purpose
The `Command` aggregate and its routing tables. A `Command` owns an `IntentQueue`, resolves a
`CommandContext` for its inbound payload, runs it once inside a `try` that turns exceptions (not `Error`s or interrupts) into a failed output, and hands the drained
effects to the application layer. `CommandType` / `CommandDetailType` are the routing enums; `CommandSet`
is the mention vocabulary.

## Key Files
| File | Description |
|------|-------------|
| `Command.kt` | `abstract class Command<T : SubCommandDefinition>(idempotencyKey, commandData)`: `internal intents`, `commandId`, `drainIntents()`, `internal abstract parseContext(subCommand)` / `findSubCommandDefinition()`, `handleEvent()` (any `Exception` except `InterruptedException` → `CommandOutput.fail(ERROR_RESPONSE, reason = exception.toString())`; for a slash payload it also stages a requester-only ephemeral in the command's channel, see below), `internal open val slashCommandName` (`""`) / `subCommandDefinitions` (empty) that slash commands override, interaction payloads dispatched to `ReactionContext.handleInteraction`, `createSubCommand()` (`options = subCommands.drop(1)`, invalid → `SubCommandParseException`). Top-level `internal fun String.asEchoedToken()` makes a user token safe to echo (clipped to `MAX_ECHOED_TOKEN_LENGTH` = 40 plus `…`, backticks turned into `'`, then `escapeMarkup`); used by the unknown-subcommand reply and by `context/form/CalendarSlashContext`'s unknown-action reply |
| `CommandSet.kt` | `internal enum CommandSet(requiredPermission)`: `UNKNOWN` (AI), `NOTICE`, `STATUS`, `USAGE` (OPERATIONS), `HELP` (BASIC), `ASK` (AI), `GRANT`, `REVOKE`, `ROLES`, `CVE` (ADMINISTRATION); `parseCommand` uppercases and falls back to `UNKNOWN` |
| `CommandType.kt` | `CommandType` (`SIMPLE`, `PIPELINE`, `RESPONSE`, `EXTERNAL_API`); `CommandDetailType` — the routing token serialized by name into the outbox column and Slack `private_metadata` / button values; `internal fun CommandDetailType.createContext(basicInfo, subCommand, intents)` maps the eight non-submission interaction types to contexts and lists every other value explicitly (no `else`) in one `EmptyContext` group; the seven `view_submission` routes are intercepted before it by `SubmissionRouting.kt`. `STANDUP_ROUTINE_LIST` / `STANDUP_ROUTINE_STOP` type the `/standup list|stop` events and their ephemeral replies only, and `AGENT_USAGE_REPORT` types the `@bot usage` event and its channel reply only, so they sit in the `EmptyContext` group; `CALENDAR_CONNECTION` (2026-10-07; the `/calendar` command (`slash/CalendarCommand`), its replies and the connect DM; `EmptyContext` in `createContext`, since no button routes to it) |
| `InteractionCommand.kt` | `InteractionCommand(appName, idempotencyKey, commandData, actorRole[, parseObserver])` — mentions and interactions; resolves a private `Route(parser, subCommandDefinition)` lazily in one exhaustive `when` over the sealed payload, so the payload is narrowed exactly once (`SlashInvocation` → `UnSupportedCommandException`); `MeetingSubCommandDefinition.NONE` for `MEETING_APPROVAL_REQUEST` / `MEETING_CREATE_REQUEST`, else `NoSubCommands` |
| `SubmissionRouting.kt` | Phase 11 routing seam: `isSubmissionRoute`, `InboundSubmission.detailType()` (the variant derives the discriminator — the envelope's own never decides a submission route), and `SubmissionRouter` — parses the variant via its `*Parsed.from` factory, builds the leaf with the non-null model, routes rejection/missing payload to `IgnoredSubmissionContext` and reports it through `SubmissionParseObserver` |
| `ReplaceTextResponseCommand.kt` | Wraps `ReplaceMessageContext(markdownMessage, replyHandle)`; built by `SlackInteractionHandlerImpl` to overwrite an already-posted message |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `context/` | `CommandContext` / `ReactionContext` bases and the mention-driven contexts (see `context/AGENTS.md`) |
| `event/` | `CommandEvent` / `EventPayload` pairs and `EventPublisher` (see `event/AGENTS.md`) |
| `parsers/` | `ContextParser` and its mention / interaction implementations (see `parsers/AGENTS.md`) |
| `slash/` | Slash-command `Command` subclasses, sub-command definitions, `MeetingListRange` (see `slash/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Routing a new interaction: add a `CommandDetailType` constant **and** a `createContext` branch.
  `createContext` has no `else`, so a new constant is a compile error until it is placed. Placing it in the
  `EmptyContext` group compiles, but an interaction of that type then fails at runtime with
  `ERROR_RESPONSE`, because `EmptyContext` is not a `ReactionContext`; that group is only for values that
  type outbound messages or events. Add a case to `InteractionContextParserTest` for a routed value. A new modal submission instead means: `InboundSubmission`
  variant + `*Parsed` model + leaf + `SubmissionRouter` branch — the exhaustive `when`s in
  `SubmissionRouting.kt` refuse to compile until the wiring is complete (the mapper's `when` still
  needs its branch, pinned by the writer→parser regression test).
- `CommandDetailType` values are persisted by name and embedded in buttons already posted to Slack;
  `SlackInteractionRequestParser` reads them back with `valueOf`. Renaming one means a local DB reset
  and dead buttons — the comment above the enum says so, keep it.
- Adding a mention keyword: `CommandSet` entry with its permission, a branch in
  `parsers/AppMentionContextParser` (exhaustive `when`, so the compiler reminds you), and a line in
  `HELP_MESSAGE`. Any token that is not a `CommandSet` name is `UNKNOWN` and goes to the AI assistant.
- `subCommands` layout is `[identifier, option, option, ...]`: `findSubCommandDefinition` reads index
  0, `createSubCommand` hands the rest to `SubCommand.options`. `RequestMeetingContext` validates the
  `list` option itself; `SubCommandDefinition.validateArguments` only checks a minimum count.
- `handleEvent()` catches every `Exception` — including the `IllegalArgumentException("Command Queue is
  empty")` the mention parser throws — and reports `exception.toString()` as `errorReason`. Two things
  propagate instead (2026-10-01): an `InterruptedException`, rethrown with the interrupt flag restored, and
  any `Error` (an out-of-memory or stack overflow is not a command failure to reply about).
- **A slash command never fails silently** (2026-10-08; before, `/meetup calendar status` produced no reply and no
  log). On a `SlashInvocation` payload the catch also offers an `OutboundMessage.Ephemeral` (recipient `null` = the
  requester) into `intents`, so it is staged through the outbox like every context's usage reply:
  `SubCommandParseException` `SUBCOMMAND_NOT_FOUND` → ``Unknown subcommand `<token>`.`` plus `Usage:` and one
  `` • `<usage>` `` line per non-blank `subCommandDefinitions` usage (the token through `asEchoedToken()`: clipped to
  40 characters plus `…`, backticks turned into `'`, then `escapeMarkup`; usages escaped too, so `<routine-name>`
  survives Slack);
  `SUBCOMMAND_NOT_VALID` → ``Usage: `<that subcommand's usage>` `` (else `Invalid arguments.` plus the lines); any
  other exception → ``Something went wrong handling `<slashCommandName>`. Please try again.`` ("that command" when the
  name is blank). Mention and interaction payloads still only return the output; `CommandExecutor` logs every
  `ERROR_RESPONSE` output at WARN for all three.
- Visibility policy: `Command`, `InteractionCommand`, `ReplaceTextResponseCommand`, the slash commands,
  `CommandType` and `CommandDetailType` are public (the application builds and reads them);
  `intents`, `parseContext`, `findSubCommandDefinition`, `CommandSet` and `createContext` are
  `internal`. Keep new plumbing `internal`.
- Constructors in the application layer: `SlackMentionEventHandlerImpl` and
  `SlackInteractionHandlerImpl` (`InteractionCommand`), `MeetingServiceImpl`, `StandupSlashServiceImpl`,
  `CveQuerySlashServiceImpl`, `CveSubscriptionSlashServiceImpl` (slash commands). The domain never
  instantiates a `Command` itself.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.entity.*'
```
Specs: `CommandTest` (queue draining, error wrapping), `InteractionCommandTest` (parser selection,
unsupported payloads), `ReplaceTextResponseCommandTest`, `RequestMeetingCommandTest`, plus
`slash/MeetingListRangeTest`. `CommandSetTest` lives one level up in
`domain/src/test/kotlin/dev/notypie/domain/command/`. Fixtures: `TestCommandFactory`,
`CommandDomainInputCreator`, `UnknownSubCommandDefinition`, `createIntentQueue()`.

### Common Patterns
- Subclasses implement only `parseContext` and `findSubCommandDefinition`; execution, error wrapping
  and draining stay in the base.
- `by lazy` for anything whose construction may throw (`InteractionCommand.route`), so the
  throw lands inside `handleEvent()`'s `catch (Exception)`.

## Dependencies

### Internal
- `command/SubCommandDefinition.kt` (`SubCommand`, `NoSubCommands`, `findSubCommandByIdentifier`),
  `command/authorization`, `command/dto`, `command/dto/response`, `command/exceptions`,
  `command/inbound`, `command/intent`, `common/error`

### External
`java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
