<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

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
| `SlackInteractionHandlerImpl.kt` | `@Service`, `@Transactional handleInteraction`: `InteractionPayloadParser.parseStringPayload` → `declineDetailErrorOrNull` (early return with the errors body) → `toInboundCommand()` → `IdempotencyCreator.create`. `LEGACY_AUTO_REJECT_TYPES = {APPLY_REQUEST, APPROVAL_REQUEST}` + `isCanceled()` → `ReplaceTextResponseCommand("Canceled.", replyHandle = responseUrl)`; otherwise `isPrimary() || isCanceled()` → `InteractionCommand(actorRole = UserRole.USER)` → `commandExecutor.execute`, and `applicationEventPublisher.publishEvent(result)` when `result.ok` |

## For AI Agents

### Working In This Directory
- **Do not extend `LEGACY_AUTO_REJECT_TYPES`.** Those two types get a handler-level "Canceled." replace
  that bypasses context routing. Every newer context handles its own REJECT button inside its
  `ReactionContext` (`domain/command/entity/context/`).
- **Every interaction runs as `UserRole.USER`.** No `CommandRoleResolver` here — buttons and modals are
  BASIC-permission flows. Anything role-gated must be reached through `@bot ...` mentions
  (`service/mention/`), never exposed as an interactive element.
- **The inline-error branch returns before idempotency and before any command.** A
  `MEETING_DECLINE_REASON` submission with `RejectReason.OTHER` and a blank `PLAIN_TEXT_INPUT` must not
  create a command or an outbox row; Slack keeps the modal open with the error on
  `DeclineReasonModalIds.DETAIL_BLOCK_ID`.
- **Payloads that are neither primary nor canceled are acked with `null` and dropped** — no command is
  executed. If a new element style needs handling, extend the `isPrimary` / `isCanceled` helpers in
  `infrastructure/impl/command/slack/`, not this branch.
- **This `@Transactional` is the boundary `BEFORE_COMMIT` listeners attach to:**
  `MeetingServiceImpl.updateParticipantAttendance` and `SlackMessageRelayServiceImpl.saveOutboxMessage`.
  `CommandExecutor` re-throws, so a failed intent resolution rolls back the decision *and* the outbox row.
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
non-primary payload, and the exact `response_action` JSON for the blank-Other decline case.

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
Spring `@Service` / `@Transactional`, `ApplicationEventPublisher`, `MultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
