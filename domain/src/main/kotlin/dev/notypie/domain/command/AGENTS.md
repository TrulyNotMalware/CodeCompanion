<!-- Parent: ../../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# domain/command

## Purpose
The transport-neutral command core. Everything a Slack (or future Discord) adapter needs to express
"a user asked for something" and "here is what should be sent back", with zero transport types.

The execution shape is: an adapter builds an `InboundCommand` → a `Command<T>` subclass parses it into
a `CommandContext` → the context runs, accumulating `CommandEffect`s (`CommandIntent` for state changes,
`OutboundMessage` for user-visible output) into an `IntentQueue` → the application layer drains the
queue and hands the effects to the infrastructure resolver/stager.

## Key Files
| File | Description |
|------|-------------|
| `SubCommandDefinition.kt` | `SubCommand` / `SubCommandDefinition` contracts — the parsed sub-command a context dispatches on |
| `EventQueue.kt` | `EventQueue` / `DefaultEventQueue` — ordered, drainable queue of resolved command events |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `authorization/` | `UserRole` (`user` ⊂ `ai_user` ⊂ `developer` ⊂ `admin`) and `CommandPermission` gating |
| `inbound/` | Neutral inbound envelopes: `InboundCommand`, `InboundKind`, `InboundPayload` (`SlashInvocation`, `MentionInvocation`), `InboundInteraction` |
| `outbound/` | Neutral outbound: `OutboundMessage` variants, `MessageContent`, `OutboundTargets`, `ModalForm`, `InteractionHandles`, `OutboundMessageStager` |
| `intent/` | `CommandEffect`, `CommandIntent` sealed hierarchy, `IntentQueue` |
| `entity/` | `Command` aggregate, `CommandSet`, `CommandType` / `CommandDetailType`, `InteractionCommand`, `ReplaceTextResponseCommand` |
| `entity/context/` | One `CommandContext` per user-visible behaviour (notice, status, approval, agent chat, CVE ops, reaction, ...) |
| `entity/context/form/` | Modal-backed contexts: request/reschedule/cancel meeting, add participant, standup setup & fill, CVE subscription, decline reason |
| `entity/parsers/` | `ContextParser` and its `AppMentionContextParser` / `InteractionContextParser` implementations |
| `entity/slash/` | Slash-command definitions: `RequestMeetingCommand`, `SetupStandupCommand`, `CveLatestSlashCommand`, `CveSubscriptionCommands`, `MeetingListRange` |
| `entity/event/` | `Event`, `EventPublisher` contracts implemented by infrastructure |
| `exceptions/` | `CommandException` hierarchy and `CommandErrorCode` |
| `dto/` | `CommandBasicInfo`, `UrlVerificationRequest` |
| `dto/modals/` | Modal payload DTOs: `ApprovalContents`, `SelectionContents`, `TextInputContents`, `TimeScheduleInfo` |
| `dto/response/` | `CommandOutput` (`empty` / `fail`) and `Status` |

## For AI Agents

### Working In This Directory
- **Adding a new user-visible behaviour** usually means: a new `CommandContext` subclass under
  `entity/context/` (or `entity/context/form/` if a modal drives it), a `CommandDetailType` entry so
  later interactions route back to it, and — if it changes state — a new `CommandIntent` variant that
  infrastructure's resolver maps to an event.
- Contexts never send anything themselves. They call `addOutbound(OutboundMessage...)` /
  `addIntent(CommandIntent...)`; the queue is drained once by `CommandExecutor` in the application layer.
- `CommandContext` and `Command.parseContext` / `findSubCommandDefinition` are `internal` on purpose.
  Keep new context plumbing `internal` unless the application layer genuinely needs it.
- `Command.handleEvent()` wraps execution in `runCatching` and converts any throw into
  `CommandOutput.fail(..., ERROR_RESPONSE, ...)`. Error effects are still drained and delivered — do not
  early-return on failure paths in a way that skips `addOutbound`.
- `drainIntents()` returns a defensive copy and clears the queue, so it is safe under retry. Intents are
  deliberately **not** re-queued after a publish failure — retries belong upstream (outbox relay, Kafka,
  Slack replay) under the shared `idempotencyKey`.
- Every symbol here must stay Slack-free (see `domain/AGENTS.md` for the enforced list).

### Testing Requirements
Specs mirror the package layout under `domain/src/test/kotlin/dev/notypie/domain/command/`:
`context/`, `entity/`, `inbound/`, `outbound/`, `parsers/`, `authorization/`.
`AbstractCommandContextTest.kt` is the shared base for context specs — extend it rather than
re-building fixtures. Input builders live in `domain/src/testFixtures/kotlin/dev/notypie/domain/command/`
(`InboundCommandCreator`, `InboundInteractionInputCreator`, `TestCommandFactory`, ...).

### Common Patterns
- Sealed classes/interfaces for exhaustive `when` (`CommandIntent`, `OutboundMessage`, `InboundPayload`).
- `data class` for every DTO; defaults for optional fields rather than overloads.
- KDoc on each intent/message variant states *who triggers it* and *where the invariant is enforced*
  (e.g. "host-only authorization enforced atomically by the repository's WHERE clause"). Keep that
  convention — it is how the enforcement point stays discoverable from the domain side.
- Threading: `OutboundMessage.ChannelMessage.threadId` non-null posts a threaded reply; the thread root
  (`thread ?: message` from a `MentionInvocation`) doubles as the stable conversation id.

## Dependencies

### Internal
- `domain/meet/` and `domain/standup/` — command intents reference their entities/enums (one-way only)
- `domain/common/` — validation DSL, error details, `IdempotencyData`

### External
None beyond the Kotlin stdlib and `java.time` / `java.util`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
