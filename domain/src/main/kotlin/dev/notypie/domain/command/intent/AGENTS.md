<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/intent

## Purpose
What a context is allowed to *want*. `CommandIntent` variants describe state changes without naming a
repository or a transport; `IntentQueue` accumulates them (together with `OutboundMessage`s) in
emission order until the application layer drains the command.

## Key Files
| File | Description |
|------|-------------|
| `CommandEffect.kt` | Marker interface implemented by `CommandIntent` and `outbound/OutboundMessage`. Not `sealed` because the two implementors live in different packages |
| `CommandIntent.kt` | `sealed class`: `MeetingListRequest`, `MeetingAttendanceUpdate`, `CancelMeeting`, `RescheduleMeeting`, `AddParticipant`, `StatusReport`, `GrantRole`, `RevokeRole`, `ListRoles`, `AgentConverse`, `RecordStandupAnswer`, `CreateStandupRoutine`, `CveSubscribe`, `CveUnsubscribe`, `CveListSubscriptions`, `CveLatest`, `CveListTopics`, `CveSetTopicActive`, `CveRetryDeadLetters`, `CveRetryDeadLetter`, `Nothing`. Each KDoc names who triggers it and where its invariant is enforced |
| `IntentQueue.kt` | `IntentQueue` (`offer`, `snapshot`, `drainSnapshot`, `isEmpty`, `size`) and `internal DefaultIntentQueue` over an `ArrayDeque`; thread-unsafe by design |

## For AI Agents

### Working In This Directory
- The consumer is `infrastructure/impl/command/SlackIntentResolver.kt`: it maps every variant to a
  `CommandEvent` from `entity/event/Event.kt` and attaches `responseBasicInfo`. Adding a variant means:
  a sealed subclass here, a payload/event pair in `entity/event/`, a resolver branch (the exhaustive
  `when` fails to compile until you add it), and an application listener.
- Intents carry business data only. Channel, app id and idempotency key ride on the command's
  `CommandBasicInfo` and are added by the resolver — do not duplicate them into intent fields.
- Enforcement points stay documented on the variant: host-only checks for `CancelMeeting` /
  `RescheduleMeeting` live in the repository `WHERE` clause, `AddParticipant`'s cap in the application
  service, `MeetingAttendanceUpdate` in a `BEFORE_COMMIT` listener. Keep that KDoc convention.
- Order in the queue is the order effects are staged. `MeetingApprovalResponseContext.handleDecline`
  emits `OpenModal` before the provisional intent so `views.open` fires inside the trigger window; do not
  sort or re-group effects when draining.
- `MeetingListRequest` defaults `startDate` / `endDate` to `now()` / `now() + 1 week` at construction;
  `RequestMeetingContext` always passes explicit bounds from `MeetingListRange`.
- `CreateStandupRoutine` is the v1 shape: every member gets the single `timezone`, and `cutoffMinutes`
  is a `Long` that the application converts to the entity's `Duration`.
- `DefaultIntentQueue.drainSnapshot()` copies then clears — that is what `Command.drainIntents()` exposes
  and why a retry after a publish failure does not re-deliver stale effects. `Nothing` is the explicit
  no-op variant for paths that must emit something.

### Testing Requirements
No spec targets the queue in isolation. Context specs assert on the drained effects through the shared
base (`AbstractCommandContextTest` uses the `createIntentQueue()` fixture and `drainSnapshot()`):
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.context.*'
```
`CommandTest` covers `drainIntents()`. Intent → event mapping is pinned by
`:infrastructure:test --tests '*SlackIntentResolverTest'`.

### Common Patterns
- `data class` per variant, `data object` for payload-free ones (`StatusReport`, `ListRoles`,
  `CveListTopics`, `CveRetryDeadLetters`, `Nothing`).
- `UUID` business keys (`meetingUid`, `sessionUid`) and `LocalDateTime` for user-facing times.

## Dependencies

### Internal
- `command/authorization/UserRole`, `meet/entity/RejectReason`

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
