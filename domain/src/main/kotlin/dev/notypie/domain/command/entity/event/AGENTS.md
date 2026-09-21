<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/entity/event

## Purpose
The event contract the rest of the system reacts to. `CommandEvent<T>` wraps an `EventPayload` with
routing metadata; the infrastructure resolver lifts each `CommandIntent` into one of the payload/event
pairs here, and application listeners consume them. `EventPublisher` is the port infrastructure
implements to deliver them.

## Key Files
| File | Description |
|------|-------------|
| `Event.kt` | `EventPayload { eventId }`; `CommandEvent<out T : EventPayload>` (`idempotencyKey`, `name`, `type: CommandDetailType`, `payload`, `destination`, `isInternal`, `timestamp`). Payload + `data class` event pairs: `GetMeetingEventPayload` / `GetMeetingListEvent` (via abstract `MeetingPayload`), `UpdateMeetingAttendance*`, `CancelMeeting*`, `RescheduleMeeting*`, `AddParticipant*`, `StatusReport*`, `RoleManage*` (+ `RoleManageAction`), `CveSubscription*` (+ `CveSubscriptionAction`), `CveLatest*`, `CveOps*` (+ `CveOpsAction`), `AgentConverse*`, `RecordStandupAnswer*`, `CreateStandupRoutine*`. Plain `data class`es that are **not** `CommandEvent`s: `StandupCutoffEvent`, `DeclineModalOpenFailedEvent`, `StandupModalOpenFailedEvent` |
| `EventPublisher.kt` | `EventPublisher.publishEvent(EventQueue<CommandEvent<EventPayload>>)` and the `publishOne(event)` extension that wraps a single event in a one-shot `DefaultEventQueue` |

## For AI Agents

### Working In This Directory
- Producer: `infrastructure/impl/command/SlackIntentResolver.kt` (one branch per `CommandIntent`).
  Publishers: `AppEventPublisher` (in-process) and `KafkaEventPublisher` in the same package.
  Consumers: application listeners under `application/service/`. A new intent needs a payload class,
  an event `data class`, and the resolver branch; keep the naming pair `XxxPayload` / `XxxRequestEvent`
  (or `XxxEvent` for state changes).
- Every payload that expects an asynchronous reply carries `responseBasicInfo: CommandBasicInfo` — the
  channel/app the listener answers on. `RecordStandupAnswerPayload` and `UpdateMeetingAttendancePayload`
  omit it because their listeners do not post.
- `type: CommandDetailType` on the event is the routing token that later interactions use to find
  their context again; it must match what the emitting context declared.
- `name` defaults to the event's simple class name and `timestamp` to `System.currentTimeMillis()` at
  construction; `isInternal` defaults to `true` for everything here. `DefaultEventQueue`
  (`command/EventQueue.kt`) counts `isInternal = false` events for `containsExternalEvent()`.
- The three plain data classes are published directly as Spring application events, not through the
  resolver: `StandupCutoffEvent` by `application/service/standup/StandupSchedulingService.kt`, the two
  `*ModalOpenFailedEvent`s by `infrastructure/impl/command/ApplicationMessageDispatcher.kt` when
  `views.open` fails. They are the fallback path for an expired trigger.
- Prefer `publishOne(event)` (30 call sites) over building a queue by hand.
- This package is where `command` legitimately depends on `meet` (`RejectReason`) and on
  `authorization/UserRole`; the reverse edge is guard-forbidden.

### Testing Requirements
No domain spec targets the event classes; they are plain data. The queue they travel in is covered by:
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.EventQueueTest'
```
Intent → event mapping is pinned by `:infrastructure:test --tests '*SlackIntentResolverTest'`, and
`TestCommandEventCreator` / `MockEventBuilderCreator` in `testFixtures` build events for listener specs.

### Common Patterns
- Payload = `class` with `override val eventId = UUID.randomUUID()` as first parameter; event =
  `data class` with the six `CommandEvent` overrides defaulted, `payload` and `type` required.
- Action enums (`RoleManageAction`, `CveSubscriptionAction`, `CveOpsAction`) collapse several intents
  into one event type with nullable fields documented per action.

## Dependencies

### Internal
- `command/EventQueue.kt`, `command/authorization/UserRole`, `command/dto/CommandBasicInfo`,
  `command/entity/CommandDetailType`, `meet/entity/RejectReason`

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
