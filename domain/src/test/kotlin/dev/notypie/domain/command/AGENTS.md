<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# domain/command (test)

## Purpose
Specs for the command core's top-level types — `CommandSet`, `SubCommand` / `SubCommandDefinition`,
`EventQueue` — with one sub-package per concern below. Together with the sub-packages this is the
largest part of the domain test set (36 of 42 spec files).

## Key Files
| File | Description |
|------|-------------|
| `CommandDomainTest.kt` | Empty `BehaviorSpec` placeholder — declares no cases. Either fill it or delete it |
| `CommandSetTest.kt` | `CommandSet.parseCommand`: unknown string → `UNKNOWN`, case-insensitive match (`NOtiCE` → `NOTICE`), `ask` → `ASK`. `BehaviorSpec`, no fixtures |
| `EventQueueTest.kt` | `DefaultEventQueue` via `createDomainEventQueue()`: empty state, `offer` of internal/external events, `containsExternalEvent` flips back when the external event is polled, FIFO `poll`, ordered `snapshot`. `BehaviorSpec`; uses `createInternalTestEvent`, `createExternalTestEvent`, `INTERNAL_EVENT_NAME`, `EXTERNAL_EVENT_NAME`, `TestCommandEvent` |
| `SubCommandDefinitionTest.kt` | `SubCommandDefinition.validateArguments` (`requiresArguments` / `minRequiredArgs`), `SubCommand.empty()` / `SubCommand.of(definition, options)` / `isValid()`, and `findSubCommandByIdentifier<MeetingSubCommandDefinition>` (match → `LIST`, `""` → `NONE`, unknown → `null`). `BehaviorSpec`; uses `NoSubCommands` from main, no fixtures |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `authorization/` | `UserRole` × `CommandPermission` grant ladder (see `authorization/AGENTS.md`) |
| `context/` | One spec per `CommandContext` (main `entity/context/` and `entity/context/form/`) (see `context/AGENTS.md`) |
| `entity/` | `Command` base contract, `InteractionCommand`, `ReplaceTextResponseCommand`, `RequestMeetingCommand` (see `entity/AGENTS.md`) |
| `entity/slash/` | `MeetingListRange` windows and token parsing |
| `inbound/` | `InboundForm` accessors and `InboundInteraction` completeness / primary / canceled rules (see `inbound/AGENTS.md`) |
| `outbound/` | Constructor round-trips for every `OutboundMessage`, `MessageContent`, `ModalForm` variant (see `outbound/AGENTS.md`) |
| `parsers/` | `AppMentionContextParser` routing + authorization matrix, `InteractionContextParser` routing (see `parsers/AGENTS.md`) |

Main packages with no spec directory here: `intent/`, `dto/`, `exceptions/`. Intents are asserted
indirectly by every context spec; the DTOs are exercised by `outbound/OutboundMessageTest`.

## For AI Agents

### Working In This Directory
- `EventQueueTest` shares one queue across its `when` blocks and is order-dependent (the `poll` cases
  assume the two earlier `offer`s). Add new cases at the end or give them their own queue.
- `CommandDomainTest.kt` is dead weight; do not add cases to it by reflex — put them next to the
  class under test.
- Package here is `dev.notypie.domain.command`, the same as main, so `internal` symbols such as
  `NoSubCommands` and `findSubCommandByIdentifier` resolve without extra plumbing.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.*'
./gradlew :domain:test --tests 'dev.notypie.domain.command.EventQueueTest'
```

### Common Patterns
- `given` = subject, `when` = input, `then` = one observable outcome; several `then`s per `when` are
  fine, each asserting one thing.
- Named arguments on every call into production code (`parseCommand(stringCommand = ...)`).
- Fixtures over inline builders: event and queue creators come from
  `domain/src/testFixtures/kotlin/dev/notypie/domain/command/TestCommandEventCreator.kt`.

## Dependencies

### Internal
- `dev.notypie.domain.command` (main) — `CommandSet`, `SubCommand`, `SubCommandDefinition`,
  `NoSubCommands`, `DefaultEventQueue`; `command.entity.slash.MeetingSubCommandDefinition`.
- `testFixtures` — `TestCommandEventCreator.kt`.

### External
- Kotest (`BehaviorSpec`, `shouldBe`, `shouldNotBe`, `shouldBeInstanceOf`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
