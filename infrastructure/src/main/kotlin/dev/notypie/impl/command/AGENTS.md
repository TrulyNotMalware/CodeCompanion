<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

# infrastructure/impl/command

## Purpose
The Slack transport adapter. Inbound: raw Slack interaction JSON → `InteractionPayload` → neutral
`InboundInteraction`, and domain `CommandIntent`s → `CommandEvent`s. Outbound: domain `OutboundMessage`s
are staged (modals rendered now, everything else enqueued) and rendered at deliver time into Slack Web API
form bodies that `ApplicationMessageDispatcher` sends. `EventPublisher` implementations live here too.

## Key Files
| File | Description |
|------|-------------|
| `SlackInteractionRequestParser.kt` | `InteractionPayloadParser` impl. Peeks the top-level `type`, then Gson snake_case into the SDK `ViewSubmissionPayload` or `BlockActionPayload`. `parseStates` maps `view.state.values` to `States` (with `blockId`); view_submission synthesizes an `APPLY_BUTTON` `currentAction`, reads routing tokens from `private_metadata`, injects an empty `STATIC_SELECT` for `MEETING_DECLINE_REASON`, and recovers the delivery channel from `routingExtras[1]` for reschedule/add-participant. block_actions reads tokens from `message.text` (or the button value on ephemeral primaries); `buttonParser` maps style `primary`/`danger` to `APPLY_BUTTON`/`REJECT_BUTTON` |
| `InteractionPayloadParser.kt` | `interface InteractionPayloadParser { parseStringPayload(payload: String): InteractionPayload }` |
| `SlackInboundMapper.kt` | `InteractionPayload.toInbound()` / `toInboundCommand()`, `States.toInboundField()` / `toInboundAction()`, `ActionElementTypes → InboundFieldKind`; `buildSubmission` builds the typed `InboundSubmission` for the seven `*_SUBMIT` / `MEETING_DECLINE_REASON` detail types, sorting standup answers by `standup_q_<index>` |
| `SlackIntentResolver.kt` | `resolveAll(intents, basicInfo)`: exhaustive `when` over `CommandIntent` (21 variants; `Nothing` → dropped) producing the matching `CommandEvent` and its routing `CommandDetailType` |
| `SlackOutboundStager.kt` | `OutboundMessageStager` impl. `OpenModal` → `stageModal` (seven `ModalForm` variants; blank `trigger_id` → `null` + warn; `StandupFill` loads routine and session via `StandupRepository`); every other family → `OutboundMessageEnqueued`, unrendered |
| `OutboundRenderer.kt` | `OutboundRenderer` port + `SlackOutboundRenderer`: `OutboundMessage` → `SlackEventPayload` via the constructor; `OpenModal` / `DirectMessage` and not-yet-migrated `MessageContent`s hit `error(...)` |
| `SlackApiEventConstructor.kt` | Builds `SendSlackMessageEvent` (message/ephemeral/action-response/`chat.update`) and `OpenViewEvent` (seven `open*ModalRequest`s) from `SlackTemplateBuilder` layouts. SDK requests become form maps via `RequestFormBuilder.toForm`; `buildRoutingText` writes `"<idempotencyKey>,<CommandDetailType>[,urlencoded extras…]"` into `message.text` |
| `ApplicationMessageDispatcher.kt` | `MessageDispatcher` impl. `dispatch` (inside `RetryService.execute`) routes `PostEventPayloadContents.messageType` to `chat.postEphemeral` / `chat.postMessage` / `chat.update` via `postFormWithTokenAndParseResponse`, and `ActionEventPayloadContents` to an OkHttp POST on `response_url`; `OpenViewPayloadContents` throws. `dispatchImmediate` = `views.open`, never throws, publishes `DeclineModalOpenFailedEvent` / `StandupModalOpenFailedEvent` on failure |
| `SlackViewOpenDispatcher.kt` | Synchronous (non-`@Async`) `@EventListener` for `OpenViewEvent` → `dispatchImmediate` |
| `KafkaEventPublisher.kt` | `EventPublisher`: `isInternal` → Spring bus, else `kafkaTemplate.send(destination, idempotencyKey, payload)` awaited `sendTimeoutMillis` (default 5000) — timeout / execution cause / interrupt are rethrown |
| `AppEventPublisher.kt` | `EventPublisher` that publishes every event on the Spring bus (default `APPLICATION_EVENT` mode) |
| `RestRequester.kt` / `RestClientRequester.kt` | Generic Spring `RestClient` wrapper: `safe*` verbs return `Result<ResponseEntity<T>>`, plain verbs `bodyOrThrow`; per-call bearer header; `SLACK_API_BASE_URL`. Only consumer: `templates/ModalTemplateBuilder` (`users.profile.get`) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `slack/` | Slack wire DTOs, `ActionElementTypes`, `InteractionTypes`, mention and slash-command mappers (see `slack/AGENTS.md`) |
| `event/` | `SlackEventPayload` family, `SendSlackMessageEvent` / `OpenViewEvent` / `OutboundMessageEnqueued`, `MessageDispatcher`, output helpers (see `event/AGENTS.md`) |
| `dto/` | `SlackUserProfileDto` for `users.profile.get` (see `dto/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **The routing-text format is a two-sided contract.** Writer: `SlackApiEventConstructor.buildRoutingText`
  (extras URL-encoded, joined with `,`) for `chat.postMessage`, and `private_metadata` for modals. Reader:
  `SlackInteractionRequestParser` splits on `,`, trims, `URLDecoder`s each extra, and `CommandDetailType
  .valueOf`s token 1 — an unknown name throws. `chatPostEphemeralBuilder` writes `"$key, $type"` with no
  extras and no encoding; on an ephemeral block_action the embedded text is the *button value*, so an
  ephemeral primary button must carry the routing string as its `value`.
- **`routingExtras` positions are per detail type** (`SlackInboundMapper.buildSubmission`): `[0]` =
  requester / participant / creator / user id, `[1]` = delivery channel (reschedule, add-participant) or
  notice channel (decline, standup answer) or command channel (standup setup), `[2]` = notice `ts`. The
  parser's `recoverDeliveryChannel` reads `[1]` only for the two meeting-host flows.
- **`chat.postEphemeral` needs `channel` = the channel and `user` = the viewer.** A user id in `channel`
  routes the ephemeral into that user's DM. For DMs, `chatPostMessageBuilder` sets `channel = targetUserId`
  and the dispatcher treats `DIRECT_MESSAGE` exactly like `CHANNEL_ALERT` (`chat.postMessage`).
- **Retry covers exceptions only.** `dispatch` runs in `RetryService.execute` with the defaults; a Slack
  `ok = false` becomes `failOutput` (logged with `error` / `warning`) and is *not* retried. A
  the invalid `PostEventPayloadContents`/action-response pairing is unrepresentable since B1 — `MessageType` no longer carries `ACTION_RESPONSE`.
- **`dispatchImmediate` fallbacks need `participantUserId`.** Every `open*ModalRequest` sets it (requester,
  creator, or publisher); blank means no failure event is published. Only `MEETING_DECLINE_REASON` and
  `STANDUP_PROMPT` have a fallback event — other modal failures are logged and returned as `failOutput`.
- **`SlackOutboundRenderer` has `else -> error("not yet migrated")` branches** for `ChannelMessage` and
  `Ephemeral` content kinds it does not render (`MeetingList` on a channel message, anything but
  `Text` / `MeetingList` on an ephemeral). A new `MessageContent` variant needs a branch here, a mix-in
  in `repository/outbox/OutboundMessageCodec`, and a template — or the outbox row sticks at deliver time.
- **`RestClientRequester.kt` declares a public top-level `val logger`** in this package. Every other file
  uses a private `log` / `dispatcherLog`; adding another top-level `logger` here is a redeclaration error.
- `SlackIntentResolver` maps several intents (`GrantRole`, `RevokeRole`, `ListRoles`, CVE ops) to
  `CommandDetailType.SIMPLE_TEXT` — routing is by event class, the detail type there is informational.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.*'
```
Specs: `SlackInteractionRequestParserTest`, `SlackInboundMapperTest`, `SlackIntentResolverTest`,
`SlackOutboundStagerTest`, `SlackOutboundRendererTest`, `SlackApiEventConstructorTest`,
`ViewSubmissionChannelRoutingRegressionTest` (guards the `private_metadata` channel recovery — never
delete), `KafkaEventPublisherTest` (`@SpringBootTest` + `EmbeddedKafka`), `RestClientRequesterTest`.
Fixtures: `testFixtures/.../impl/command/BlockActionPayloadCreator`, `slack/InteractionPayloadCreator`,
`slack/SlackEventCallBackRequestCreator`, `event/SlackEventTestFixtures`. There is no spec for
`ApplicationMessageDispatcher`, `SlackViewOpenDispatcher`, or `AppEventPublisher`.

### Common Patterns
- Exhaustive `when` over domain sealed types (`CommandIntent`, `OutboundMessage`, `ModalForm`) — no
  `else` except the explicit `error(...)` guards in the renderer.
- Slack SDK types stay inside this package: build with SDK request builders, flatten to `Map` /
  `String` bodies before the payload leaves.
- `runCatching` + `fold` around SDK calls that must not throw (`dispatchImmediate`); named arguments.

## Dependencies

### Internal
- `domain/command/*` — `CommandIntent`, `OutboundMessage`, `ModalForm`, `MessageContent`, inbound model,
  `CommandEvent` / `EventPayload` / `EventPublisher`, `CommandBasicInfo`, `CommandDetailType`,
  `CommandOutput`, modal-open-failed events
- `impl/command/event`, `impl/command/slack`, `impl/command/dto`, `impl/retry/RetryService`
- `repository/standup/StandupRepository` (stager), `templates/` (`SlackTemplateBuilder`, `ButtonType`,
  `dto/LayoutBlocks`)

### External
Slack Java SDK (`slack-api-client`: `Slack`, `RequestFormBuilder`, `GsonFactory`, chat responses;
`slack-app-backend`: `BlockActionPayload`, `ViewSubmissionPayload`, `ActionResponse`), OkHttp, Gson,
Spring Kafka `KafkaTemplate`, Spring `ApplicationEventPublisher` / `@EventListener`, Spring Web
`RestClient`, `kotlin-logging`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
