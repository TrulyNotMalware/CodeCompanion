<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# test/kotlin/dev/notypie/application/service/interaction

## Purpose
Spec for `SlackInteractionHandlerImpl.handleInteraction`, the block-action/view-submission router. It locks
the legacy auto-reject whitelist, proves context-routed types go through `InteractionCommand` for both
APPLY and REJECT, and covers the decline-reason modal validation that returns a `response_action` errors body.

## Key Files
| File | Description |
|------|-------------|
| `SlackInteractionHandlerImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK; the handler gets a real H2 `DataSourceTransactionManager`. Deferral: a command that queues a meeting write runs in the interaction transaction, the write runs only after that transaction completed and outside any transaction; if the command throws after queueing, the request fails and the write never runs; a checked `IOException` after queueing rolls the interaction back (`STATUS_ROLLED_BACK`) and the write never runs; with a MockK transaction manager whose `rollback` throws, the original exception escapes with the rollback failure as its only suppressed exception. A command that publishes an `OpenViewEvent` through a real `SlackViewOpenDispatcher` gets `views.open` only after the interaction transaction completed and outside any transaction (dropping the handler's `ViewOpenDeferral.afterBoundary` fails it). Pool scenario (pool of 3, 3 concurrent interactions that all hold their connection on a barrier, and in the old shape keep holding it on a second barrier until every write has been tried, so a connection released by an early timeout cannot rescue a slower thread under CPU load): the old shape — `addParticipants` inside each interaction transaction — commits 3× "Failed to add participants. Please try again later." (every isolated write times out); through `handleInteraction` it commits 3× "Added <@U_A> to the meeting.". Existing cases: `LEGACY_AUTO_REJECT_TYPES` equals `{APPLY_REQUEST, APPROVAL_REQUEST}` and excludes `MEETING_APPROVAL_REQUEST`, `MEETING_CREATE_REQUEST`, `APPROVAL_CALLBACK`, `NOTHING`; constructor case asserts `handler != null` (placeholder). APPLY on `MEETING_CREATE_REQUEST` → `execute(InteractionCommand)` once, `ReplaceTextResponseCommand` never; REJECT on `APPROVAL_REQUEST` → `ReplaceTextResponseCommand` once (legacy "Canceled." path); `MEETING_DECLINE_REASON` with `RejectReason.OTHER` and blank `PLAIN_TEXT_INPUT` → returns an ack body containing `"response_action":"errors"` and `DeclineReasonModalIds.DETAIL_BLOCK_ID`, no execute; OTHER with `Visiting family abroad` → returns null, `InteractionCommand` executed; APPLY and REJECT on `MEETING_APPROVAL_REQUEST` and REJECT on `MEETING_CREATE_REQUEST` → `InteractionCommand`, never the legacy command. |

## For AI Agents

### Working In This Directory
- The parser is mocked: `payloadParser.parseStringPayload(payload = any())` returns the fixture, so the
  literal `"dummy-payload"` string is never parsed. Routing is driven entirely by
  `createInteractionPayloadInput(commandDetailType, currentAction, states, idempotencyKey)`.
- Mocks are spec-scoped and every `then` starts with
  `clearMocks(payloadParser, commandExecutor, applicationEventPublisher)`; without it the `exactly = 0`
  checks count executions from earlier cases.
- Command type is matched with `match<Command<*>> { it is InteractionCommand }`; the generic parameter is
  required or MockK cannot infer the `execute` overload.
- The decline-reason cases build `States(type = STATIC_SELECT / PLAIN_TEXT_INPUT, isSelected, selectedValue)`
  inline; the fixture's `selectedApplyButtonStates()`/`selectedRejectButtonStates()` cover the button cases.
- The "constructor resolves without error" case is a no-op assertion and the only placeholder in this spec.
- The class-level KDoc explains why the whitelist is pinned: a new `CommandDetailType` must not inherit the
  global "Canceled." short-circuit without opting in.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.interaction.*'
```
Fixtures used: `infrastructure` testFixtures `impl/command/slack/InteractionPayloadCreator.kt`
(`createInteractionPayloadInput`, `selectedApplyButtonStates`, `selectedRejectButtonStates`). No
`application` or `domain` fixtures.

### Common Patterns
- Each routing case is one `given` → one `when` → one `then` that contains both the setup and the verifies;
  the `then` is the unit because `clearMocks` must run inside it.
- Ack bodies are asserted as raw JSON substrings (`shouldContain`), not by deserializing.

## Dependencies

### Internal
- `application/service/interaction/SlackInteractionHandlerImpl.kt`,
  `application/service/command/CommandExecutor`
- `domain/command/entity/Command`, `InteractionCommand`, `ReplaceTextResponseCommand`, `CommandDetailType`,
  `domain/meet/entity/RejectReason`
- `infrastructure/impl/command/InteractionPayloadParser`, `impl/command/slack/States`, `ActionElementTypes`,
  `templates/InteractiveIds.kt` (`DeclineReasonModalIds`)

### External
MockK (`clearMocks`), Kotest, Spring `ApplicationEventPublisher`, `LinkedMultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
