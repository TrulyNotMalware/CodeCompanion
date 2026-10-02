<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# application/service/interaction

## Purpose
Handles every Slack interactive payload — button clicks (`block_actions`) and modal submissions
(`view_submission`) — by parsing it into an `InboundCommand`, wrapping it in an `InteractionCommand`, and
running it through `CommandExecutor`. It is also where a `view_submission` can be answered synchronously:
the decline-reason modal's blank "Other" detail returns a `response_action: errors` body instead of
persisting anything.

## Key Files
| File | Description |
|------|-------------|
| `InteractionHandler.kt` | Interface `handleInteraction(headers, payload: String): String?` — `null` is the normal empty ack; a non-null string is the `response_action` JSON the caller must relay to Slack (HTTP 200 body in `SlackEventController`, ack body in `SocketModeReceiver.handleInteractive`) |
| `SlackInteractionHandlerImpl.kt` | `@Service` taking the `PlatformTransactionManager`. `handleInteraction` opens the interaction transaction programmatically (`DefaultTransactionDefinition`, REQUIRED, rolls back on every `Throwable`, a checked exception included: committing on one kept the interaction's rows while `MeetingWriteDeferral.collecting` dropped the meeting writes queued behind them; a rollback that fails too is attached to the original as suppressed instead of replacing it) inside `MeetingWriteDeferral.collecting { }`, and after that transaction has committed and released its connection runs the meeting writes the command queued (cancel, reschedule, add-participant); if the transaction fails they are dropped. Called inside an already active transaction it joins it and nothing is deferred. Before the transaction opens: `InteractionPayloadParser.parseStringPayload` → `declineDetailErrorOrNull` (early return with the errors body) → `commandFor`: `toInboundCommand()` → `IdempotencyCreator.create`, then `LEGACY_AUTO_REJECT_TYPES = {APPLY_REQUEST, APPROVAL_REQUEST}` + `isCanceled()` → `ReplaceTextResponseCommand("Canceled.", replyHandle = responseUrl)`, `isPrimary() || isCanceled()` → `InteractionCommand(actorRole = commandRoleResolver.resolve(actorId), parseObserver = MeteredSubmissionParseObserver)`, anything else → nothing to do (returns null, no transaction). The role is resolved there, outside the transaction: a failed query inside it would mark it rollback-only and the resolver's `USER` fallback would still end in a 500 at commit. Inside the transaction only `commandExecutor.execute`, and `applicationEventPublisher.publishEvent(result)` when an `InteractionCommand`'s `result.ok` |
| `MeteredSubmissionParseObserver.kt` | `@Component` binding the domain `SubmissionParseObserver` port to Micrometer: `codecompanion.submission.ignored` counter tagged `detail_type` × `reason` plus a debug log; raw form values never reach a tag or log line |

## For AI Agents

### Working In This Directory
- **Do not extend `LEGACY_AUTO_REJECT_TYPES`.** Those two types get a handler-level "Canceled." replace
  that bypasses context routing. Every newer context handles its own REJECT button inside its
  `ReactionContext` (`domain/command/entity/context/`).
- **The actor's role is resolved per interaction** through `CommandRoleResolver` (a DB read, which is also
  when the interaction transaction takes its pooled connection).
- **The inline-error branch returns before idempotency and before any command.** A
  `MEETING_DECLINE_REASON` submission with `RejectReason.OTHER` and a blank `PLAIN_TEXT_INPUT` must not
  create a command or an outbox row; Slack keeps the modal open with the error on
  `DeclineReasonModalIds.DETAIL_BLOCK_ID`.
- **Payloads that are neither primary nor canceled are acked with `null` and dropped** — no command is
  executed. If a new element style needs handling, extend the `isPrimary` / `isCanceled` helpers in
  `infrastructure/impl/command/slack/`, not this branch.
- **The interaction transaction is the boundary `BEFORE_COMMIT` listeners attach to:**
  `MeetingServiceImpl.updateParticipantAttendance` and `SlackMessageRelayServiceImpl.saveOutboxMessage`.
  `CommandExecutor` re-throws, so a failed intent resolution rolls back the decision *and* the outbox row.
  Every command keeps that atomicity; only the three meeting writes leave it.
- **Modals open after the interaction commits** (`ViewOpenDeferral.afterBoundary` inside `MeetingWriteDeferral.collecting`), so
  `views.open` (up to the 6 s Slack call timeout) never holds the interaction's pooled connection; they open before the
  deferred meeting writes run, to stay inside `trigger_id`'s 3 s.
- **Meeting writes run after the interaction commits** (`service/meeting/MeetingWriteDeferral`). Inside the
  interaction transaction they would need a second pooled connection per request (N concurrent interactions
  on a pool of N all time out — reproduced in the spec) and could commit their write and reply while the
  interaction still rolls back to a 500. Deferred, each thread holds one connection at a time, a failed
  interaction never writes, and the write, its effects and its reply commit together in one retryable
  transaction. Residual: once the interaction has committed, a deferred write that fails after its retry
  answers the host with "Please try again later" and HTTP 200; an exception while staging that reply
  surfaces as a 500 although the interaction's own rows are committed. Do not wrap this method in
  `@Transactional` again: a proxy transaction would enclose the deferred writes.
- `applicationEventPublisher.publishEvent(result)` (marked `FIXME Event publisher`) publishes the raw
  `CommandOutput`; no `@EventListener` currently consumes it, so removing it is safe once the FIXME is
  resolved, but check listeners first.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.interaction.SlackInteractionHandlerImplTest'
```
Spec under `application/src/test/kotlin/dev/notypie/application/service/interaction/`. Fixture:
`createInteractionPayloadInput` (`dev.notypie.impl.command.slack`, infrastructure testFixtures). MockK
`InteractionPayloadParser` and `CommandExecutor`; assert which command type reaches `execute`
(`ReplaceTextResponseCommand` for legacy reject vs `InteractionCommand`), that nothing is executed for a
non-primary payload, and the exact `response_action` JSON for the blank-Other decline case. The handler takes a
real `createH2TransactionManager()`; the deferral and pool cases use `createBoundedH2DataSource`,
`CommitRecordingEventPublisher` and a real `MeetingServiceImpl` from `service/meeting` fixtures.

### Common Patterns
- Interface + `Impl` pair; the controller and socket receiver depend on the interface.
- `IdempotencyCreator.create(data = commandData)` before building any command.
- `SLACK_APP_NAME` is imported from `SlackMentionEventHandlerImpl.Companion` — one app name for all
  inbound commands.
- `jsonMapper.writeValueAsString(mapOf(...))` for the Slack `response_action` body.

## Dependencies

### Internal
- `application/service/command/CommandExecutor`, `application/common/IdempotencyCreator`,
  `application/service/mention/SlackMentionEventHandlerImpl.SLACK_APP_NAME`
- `infrastructure/impl/command/` — `InteractionPayloadParser`, `SlackInboundMapper.toInboundCommand`,
  `slack/InteractionPayload`, `slack/ActionElementTypes`, `slack/isPrimary` / `isCanceled`
- `infrastructure/templates/InteractiveIds.kt` — `DeclineReasonModalIds`
- `infrastructure/common/jsonMapper`
- `domain/command/entity/` — `InteractionCommand`, `ReplaceTextResponseCommand`, `CommandDetailType`,
  `authorization/UserRole`; `domain/command/inbound/InboundCommand`; `domain/meet/entity/RejectReason`

### External
Spring `@Service`, `PlatformTransactionManager` / `DefaultTransactionDefinition` /
`TransactionSynchronizationManager`, `ApplicationEventPublisher`, `MultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
