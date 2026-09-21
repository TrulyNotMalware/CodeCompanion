<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/entity/slash

## Purpose
The `Command` subclasses behind slash commands (`/meetup`, `/standup setup`, `/latest`, `/subscribe`,
`/unsubscribe`, `/subscriptions`), their `SubCommandDefinition` enums, the `/meetup list` date-range
grammar, and the one `CommandOutput` subclass that carries a built `Meeting` back to the application.

## Key Files
| File | Description |
|------|-------------|
| `RequestMeetingCommand.kt` | `RequestMeetingCommand` (`/meetup`): resolves `MeetingSubCommandDefinition` from `subCommands[0]` (unknown → `SUBCOMMAND_NOT_FOUND`), context `RequestMeetingContext`. `MEETING_COMMAND_IDENTIFIER = "meetup"`; `enum MeetingSubCommandDefinition` `NONE` / `LIST("list")`; `RequestMeetingContextResult(ok, status, meeting, commandBasicInfo) : CommandOutput` fixed to `PIPELINE` / `MEETING_CREATE_REQUEST` |
| `SetupStandupCommand.kt` | `SetupStandupCommand` (`/standup setup`): reads `SlashInvocation.trigger` and builds `RequestStandupSetupContext` (modal open). `STANDUP_COMMAND_IDENTIFIER = "standup"`; `enum StandupSubCommandDefinition` `NONE` / `SETUP("setup")` |
| `CveLatestSlashCommand.kt` | `/latest [topic-key]` — no modal; `topicKey` pre-resolved by the application service → `RequestCveLatestContext` |
| `CveSubscriptionCommands.kt` | `CveSubscribeSlashCommand(topics)` and `CveUnsubscribeSlashCommand(topics)` open modals from `SlashInvocation.trigger`; `CveSubscriptionsSlashCommand` emits the list intent directly |
| `MeetingListRange.kt` | `internal enum` `TODAY` / `TOMORROW` (day-aligned) / `WEEK` (`now + 7d`) / `MONTH` (`now + 30d`), half-open `[start, end)`; `DEFAULT = WEEK`; `parseOrNull(token)`; `usageTokens()` |

## For AI Agents

### Working In This Directory
- These commands are constructed by application services with data already resolved
  (`topics`, `topicKey`) — the domain never queries persistence. Keep that shape: look things up in the
  service, pass values into the constructor.
- Modal-opening commands cast `commandData.payload as SlashInvocation`. A non-slash payload throws
  `ClassCastException`, which `Command.handleEvent()` turns into an `ERROR_RESPONSE`; the modal must be
  requested synchronously because the trigger handle expires in about three seconds.
- `subCommands` is `[identifier, options...]`. `LIST` has `requiresArguments = false`, so `/meetup
  list` alone is valid and the optional range token is validated in
  `RequestMeetingContext.runListSubCommand` ("Too many arguments" / "Unknown range").
- `MeetingListRange.WEEK` / `MONTH` are rolling windows from `now`, not calendar weeks/months; `TODAY` /
  `TOMORROW` snap to midnight. Specs pass `now` explicitly to `dateRange(now)`.
- `RequestMeetingContextResult.ok` is always `true` (`RequestMeetingContext.interactionResults`
  hard-codes it) even when `status == FAILED`; `MeetingServiceImpl.createNewMeeting` must branch on
  `status`.
- `createContext(STANDUP_SETUP_REQUEST)` in `entity/CommandType.kt` rebuilds `RequestStandupSetupContext`
  with a blank trigger for completeness; the real entry point is `SetupStandupCommand`.
- Slash-command *identifiers* (`meetup`, `standup`) are matched by the application layer; the constants
  here are `internal` and used only for usage strings.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.entity.RequestMeetingCommandTest' \
  --tests 'dev.notypie.domain.command.entity.slash.MeetingListRangeTest'
```
`SubCommandDefinitionTest` (`domain/src/test/kotlin/dev/notypie/domain/command/`) covers
`validateArguments` and `findSubCommandByIdentifier`. `SetupStandupCommand` and the CVE slash commands
have no domain spec — their behaviour is pinned through the contexts they build
(`context/StandupSetupSubmissionContextTest`, `CveSubscriptionSubmissionContextTest`) and the
application service specs.

### Common Patterns
- `enum XxxSubCommandDefinition : SubCommandDefinition` with a `NONE` entry (blank identifier) so a bare
  slash command still resolves; `findSubCommandByIdentifier<T>()` does the lookup.
- `Command<NoSubCommands>` for commands with no sub-command grammar.

## Dependencies

### Internal
- `command/SubCommandDefinition.kt`, `command/entity/Command`, `command/entity/context/form/*`,
  `command/exceptions`, `command/inbound`, `command/dto`, `command/dto/response`,
  `command/outbound/TopicOption`, `common/error`, `meet/entity/Meeting`

### External
`java.time.LocalDateTime`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
