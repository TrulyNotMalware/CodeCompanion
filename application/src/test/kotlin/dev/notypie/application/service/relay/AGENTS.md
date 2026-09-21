<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-21 -->

# test/kotlin/dev/notypie/application/service/relay

## Purpose
Specs for the outbox relay: `OutboxPayloadRenderer` decodes a stored row and hands it to the transport
renderer at deliver time, `PollingMessageProcessor` reads PENDING/stuck rows and claims them before dispatch,
and `SlackMessageRelayServiceImpl` persists enqueued messages, renders and dispatches claimed rows, and
transitions row status from publish events.

## Key Files
| File | Description |
|------|-------------|
| `OutboxPayloadRendererTest.kt` | Plain Kotest `BehaviorSpec` + MockK, with a real `CodecOutboundMessagePort` producing rows. Registered `Transport.SLACK` renderer → the decoded `message`/`basicInfo` reach `slackRenderer.render` and its result is returned; `schemaVersion = 9999` → `IllegalArgumentException` mentioning `Unsupported outbox schemaVersion=9999` and `Refusing to dispatch`; empty renderer map → `IllegalStateException` `No renderer registered for transport=SLACK`. |
| `PollingMessageProcessorTest.kt` | Plain Kotest `BehaviorSpec` + MockK via `createPollingProcessorFixture(clock)` (batch 100, stuck 300 s). No pending, no stuck → no `batchPendingMessages`, no `claimPending`; three candidates and `claimPending(listOf("e1","e2","e3")) = 3` → all three forwarded once; claim count 2 of 3 → only the first two dispatched; claim count 0 → nothing dispatched; two stuck IN_PROGRESS orphans → re-dispatched without any claim, `confirmVerified(relayService)`. |
| `SlackMessageRelayServiceImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK with a real `RetryService(RetryTemplate())` and a synchronous `Executor { it.run() }`. `saveOutboxMessage(OutboundMessageEnqueued)` → `port.toRow(message, basicInfo)` and `save(row)` once each; `batchPendingMessagesAsync`: render + dispatch ok → `MessagePublishSuccessEvent(eventId = row UUID, messageTs = "1700000000.000200")` published; renderer throws → `MessagePublishFailedEvent` for the row and `dispatch` never; `eventId = "not-a-uuid"` → skipped, no render, no publish. `updateOutboxMessageStatus`: row found → `findById`, `updateMessageStatus(SUCCESS)` on the row mock, `save`; row missing → exception swallowed, `save` never. |

## For AI Agents

### Working In This Directory
- `createOutboxRow(eventId)` is a `relaxed` `OutboxMessage` mock, which is why
  `verify { storedMessage.updateMessageStatus(status = SUCCESS) }` works and why the renderer spec builds a
  real `OutboxMessage(...)` for the schema-version case instead.
- `pollPending()` is the poller's typed entry point (no parameter since A4). Stubs use
  `limit = 100, offset = 0` and `findStuckInProgress(limit = 100)` because the fixture's `batchSize`
  default is 100.
- The claim-count cases encode the race contract: dispatch exactly `claimPending` rows, taken from the front
  of the candidate list, and never claim stuck IN_PROGRESS rows.
- The relay service uses a real `RetryTemplate()`; the "row no longer exists" case walks the default retry
  policy before the exception is caught by an empty `catch`, so it is slower than the rest and asserts only
  that `save` was never called.
- The success-event case pins `eventId` to the row's UUID, not to anything the renderer minted; the renderer
  mock returns an unrelated `SlackEventPayload` on purpose.
- The renderer spec asserts exception text with `require(ex.message!!.contains(...))` rather than Kotest
  matchers; a failure surfaces as `IllegalArgumentException` from `require`, not as an assertion error.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.relay.*'
```
Fixtures used: `application` testFixtures `outbox/OutboxTestFixtures.kt` (`createFixedUtcClock`,
`createOutboxRow`, `createPollingProcessorFixture` / `PollingProcessorFixture`), `domain` testFixtures
`command/CommandDomainInputCreator.kt` (`createCommandBasicInfo`).

### Common Patterns
- `val (outboxRepository, relayService, processor) = createPollingProcessorFixture(clock = clock)` at the
  top of every polling case; the relay service inside is `relaxed`, so only positive `verify`s and
  `confirmVerified` are meaningful.
- Publish events are captured with `slot<Any>()` on `ApplicationEventPublisher.publishEvent` and narrowed
  with `shouldBeInstanceOf`.

## Dependencies

### Internal
- `application/service/relay/OutboxPayloadRenderer.kt`, `PollingMessageProcessor.kt`, `MessageProcessor.kt`
  (marker), `SlackMessageRelayServiceImpl.kt`, `application/configurations/AppConfig.Outbox.Polling`
- `infrastructure/repository/outbox/MessageOutboxRepository`, `OutboundMessagePort`,
  `CodecOutboundMessagePort`, `Transport`, `schema/OutboxMessage`, `MessageStatus`,
  `dto/MessagePublishSuccessEvent`, `MessagePublishFailedEvent`, `impl/command/OutboundRenderer`,
  `impl/command/event/MessageDispatcher`, `OutboundMessageEnqueued`, `SlackEventPayload`,
  `impl/retry/RetryService`
- `domain/command/dto/response/CommandOutput`, `domain/command/outbound/OutboundMessage`

### External
MockK (`confirmVerified`), Kotest, Spring `ApplicationEventPublisher`,
`org.springframework.core.retry.RetryTemplate`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
