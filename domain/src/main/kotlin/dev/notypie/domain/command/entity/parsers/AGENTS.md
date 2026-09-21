<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-21 -->

# domain/command/entity/parsers

## Purpose
Turn an inbound payload into the one `CommandContext` that should run. `AppMentionContextParser` is
the mention grammar (keyword, permission gate, argument shapes, usage text); `InteractionContextParser`
is a thin lookup from `CommandDetailType` to context.

## Key Files
| File | Description |
|------|-------------|
| `ContextParser.kt` | `internal interface ContextParser { parseContext(idempotencyKey): CommandContext<out SubCommandDefinition> }` |
| `AppMentionContextParser.kt` | `internal class (commandData, mention, idempotencyKey, intents, actorRole)`. Constants `HELP_MESSAGE`, `GRANT_USAGE`, `REVOKE_USAGE`, `ROLES_USAGE`, `CVE_USAGE`. Flow: no command structure → "Command Not supported."; first token → `CommandSet`; permission gate → denial text; then per-keyword dispatch: `notice`, `approval`, `help`, `status`, `ask`, `grant @user <role>`, `revoke @user`, `roles`, `cve topics` / `cve topic activate|deactivate <key>` / `cve retry all|<event-id>`, and the free-text fallback to `AgentChatContext` |
| `InteractionContextParser.kt` | `internal class (commandData, interaction, idempotencyKey, intents[, observer])`; tries `SubmissionRouter.route(interaction)` first (submission variants win, SUBMIT-without-submission → `IgnoredSubmissionContext`), then falls back to `interaction.detailType.createContext(...)` with `SubCommand.empty()` |

## For AI Agents

### Working In This Directory
- Parsers return a context and never run it; `entity/InteractionCommand` builds them lazily so a
  parse failure surfaces as an `ERROR_RESPONSE` output instead of a construction exception.
- `HELP_MESSAGE` is asserted verbatim by `AppMentionContextParserTest`; it is also the only user-facing
  documentation of the mention grammar. Change the text and the spec together, and add a line whenever
  you add a keyword.
- The permission check runs before the `when`, so a denied `grant` never reaches the argument checks.
  Usage errors after the gate are `TextResponseContext` **channel** messages, not ephemerals.
- `ask` drops the keyword from the prompt; the `UNKNOWN` fallback keeps every token ("what does status
  mean" must not lose "what"). `threadId` is `(thread ?: message)?.raw` — a top-level mention anchors a
  new thread at itself.
- Argument shapes are strict: `grant` needs exactly one mentioned user and exactly two tokens; `revoke`
  one user and one token; `roles` one token; `cve retry <id>` needs a `Long`. Topic keys are lowercased
  before they reach `CveSetTopicActive`.
- `mention.commandTokens` and `mentionedUserIds` are already bot-filtered and split by
  `infrastructure/impl/command/SlackInboundMapper.kt`; `hasCommandStructure == false` means Slack sent
  no `rich_text_section`, and an empty token list with structure throws `IllegalArgumentException`
  (caught upstream).
- `InteractionContextParser` passes an empty `SubCommand`; `createContext` substitutes
  `MeetingSubCommandDefinition.NONE` for `MEETING_CREATE_REQUEST` itself. The `else -> EmptyContext`
  arm there resolves an unrouted non-submission interaction to `EmptyContext`, which
  `Command.executeInteraction()` turns into an `ERROR_RESPONSE` (it is not a `ReactionContext`);
  submission routes can no longer fall through to it — `SubmissionRouter` intercepts them first.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.parsers.*'
```
`AppMentionContextParserTest` (keyword routing, permission denial, usage strings, help text, CVE
sub-dispatch), `InteractionContextParserTest` (detail type → context class, submission precedence)
and `SubmissionRouterTest` (variant routing, ignore reasons, variant-wins policy). Mention inputs come
from `InboundCommandCreator` in `testFixtures`; specs live under
`domain/src/test/kotlin/dev/notypie/domain/command/parsers/`.

### Common Patterns
- One private `xxxContext()` builder per keyword returning the concrete context type, with early
  `return usageContext(...)` guards for malformed arguments.
- `commandData.extractBasicInfo(idempotencyKey = idempotencyKey)` recomputed per context rather than
  cached, so every context gets the same key.

## Dependencies

### Internal
- `command/authorization`, `command/entity/CommandSet`, `command/entity/CommandType.createContext`,
  `command/entity/context/*`, `command/inbound`, `command/intent`

### External
`java.util` (`LinkedList`, `UUID`) only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
