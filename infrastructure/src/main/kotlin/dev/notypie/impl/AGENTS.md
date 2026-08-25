<!-- Parent: ../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# infrastructure/impl

## Purpose
The adapter layer proper. `impl/command` is the Slack transport adapter — it maps Slack wire payloads
into the domain's neutral inbound model, resolves domain intents into command events, renders neutral
outbound messages into Slack payloads, and publishes events. `impl/agent` talks to the AI sidecar,
`impl/cve` to external CVE feeds, and `impl/retry` provides bounded retry support.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | Slack transport adapter (inbound mapping, intent resolution, staging, rendering, dispatch, publishing) |
| `command/slack/` | Slack wire DTOs: `InteractionPayload`, `SlackEventCallBackRequest`, `EventCallbackData`, `Block`, `Element`, `States`, `Users`, `Container`, `Authorization`, `SlackEventType`, `ActionElementTypes`, `InteractionTypes`, `SlashCommandRequestBody`, `SlackMentionMapper` |
| `command/event/` | Staged-event envelopes and dispatch: `SlackCommandEvents`, `SlackEventPayloads`, `SlackCommandOutputs`, `OutboundMessageEnqueued`, `MessageDispatcher` |
| `command/dto/` | `SlackUserProfileDto` |
| `agent/` | `AgentGateway` port + `SidecarAgentClient` (HTTP + SSE) |
| `cve/` | `SourceAdapter` port + `NvdCveSourceAdapter`, `GithubReleaseSourceAdapter` |
| `retry/` | `RetryService` |

## Key Files
| File | Description |
|------|-------------|
| `command/SlackInboundMapper.kt` | Slack `InteractionPayload` → neutral `InboundInteraction` / `InboundForm` |
| `command/SlackIntentResolver.kt` | `CommandIntent` → the matching `CommandEvent` + routing `CommandDetailType`; the big `when` at the heart of the pipeline |
| `command/SlackOutboundStager.kt` | Implements `OutboundMessageStager`: stages modals synchronously, enqueues everything else unrendered |
| `command/OutboundRenderer.kt` | `OutboundRenderer` port + `SlackOutboundRenderer` — renders message-family effects at deliver time |
| `command/SlackApiEventConstructor.kt` | Shared builder used by both the stager and the renderer so their wire output is byte-identical |
| `command/ApplicationMessageDispatcher.kt` / `SlackViewOpenDispatcher.kt` | Actual Slack Web API calls (`chat.*`, `views.open`) |
| `command/KafkaEventPublisher.kt` / `AppEventPublisher.kt` | `EventPublisher` implementations — Kafka for external events, Spring bus for internal ones |
| `command/InteractionPayloadParser.kt` / `SlackInteractionRequestParser.kt` | Raw Slack request → typed payload |
| `command/RestRequester.kt` / `RestClientRequester.kt` | Thin Slack Web API HTTP client |

## For AI Agents

### Working In This Directory
- **Rendering has exactly two doors, and they share a builder.** `SlackOutboundStager` renders **only**
  modals (because `views.open` needs the request thread's `trigger_id`, which expires ~3s after
  issuance) and stages them into an `OpenViewEvent`. Every other family is wrapped unrendered in
  `OutboundMessageEnqueued`, persisted transport-neutral to the outbox, and rendered by
  `SlackOutboundRenderer` at deliver time. Both go through `SlackApiEventConstructor` so their output is
  byte-identical. Adding a third rendering path is a bug, not a feature.
- **`SlackOutboundRenderer` fails loudly** on modal and direct-message families — they are not renderer
  concerns. Keep it that way rather than adding a silent fallback.
- **`SlackInboundMapper` field order is semantic.** `InboundForm.fields` mirrors the parser's `states`
  order, and several domain contexts read it positionally (e.g. the meeting form's start/end TIME
  pickers). Reordering or filtering fields silently breaks those contexts — see
  `ViewSubmissionChannelRoutingRegressionTest`.
- **`SourceAdapter.fetch` must never throw.** A malformed `source_config` or a non-2xx/transport failure
  returns an empty list, so one bad topic cannot abort a collection tick (the collector's per-topic
  `runCatching` is the second line of defense). Adapters are selected by `supports(sourceType)`; add a
  source by adding an adapter bean, not by editing the collector.
- **`SidecarAgentClient` folds an SSE stream** (`session` → N×`text`/`tool_use`/`tool_result` → terminal
  `done`|`error`) into one `AgentTurnResult`. Wire field names are camelCase per the sidecar's
  `openapi.yaml`, which is the contract source of truth. The client-side `requestTimeout` is a safety
  net only — the real turn ceiling is server-side (`TURN_TIMEOUT_SEC`), so keep the client timeout
  comfortably above it.
- **`KafkaEventPublisher` awaits sends with a bounded timeout on purpose**, so broker failures surface
  as exceptions to `CommandExecutor` and can roll back transactionally. Do not make sends fire-and-forget.
- Slack vocabulary stops at this package boundary. Anything crossing into `domain` must already be
  neutral (`replyHandle`, `triggerHandle`, `MessageHandle`, ...).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.*'
```
Specs live beside their subject: `SlackInboundMapperTest`, `SlackIntentResolverTest`,
`SlackOutboundStagerTest`, `SlackOutboundRendererTest`, `SlackApiEventConstructorTest`,
`SlackInteractionRequestParserTest`, `slack/ElementTest`, `slack/SlackMentionMapperTest`,
`agent/SidecarAgentClientTest`, `cve/*SourceAdapterTest`, `retry/RetryServiceTest`,
`KafkaEventPublisherTest` (EmbeddedKafka), `RestClientRequesterTest`.
When you add an `OutboundMessage` or `CommandIntent` variant in the domain, the resolver/stager/renderer
`when` branches here are what make it real — add all three plus their specs, or the effect is silently
dropped.

### Common Patterns
- Port interface next to its implementation in the same file (`OutboundRenderer` / `SlackOutboundRenderer`,
  `SourceAdapter` / adapters, `AgentGateway` / `SidecarAgentClient`).
- Exhaustive `when` over the domain's sealed hierarchies — no `else ->` catch-alls that would swallow a
  newly added variant.
- KDoc explains the *constraint* behind a design (trigger_id expiry, timeout ownership, dedup key), not
  the mechanics. Preserve that when editing.
- JDK `HttpClient` for the sidecar's SSE stream; Spring `RestClient` for ordinary Slack/HTTP calls.

## Dependencies

### Internal
- `domain/command/*` — intents, outbound messages, inbound model, events, `CommandDetailType`
- `infrastructure/repository/` — outbox port, standup repository (used by the stager), CVE repositories
- `infrastructure/templates/` — modal and message block construction
- `infrastructure/common/` — shared `jsonMapper`

### External
Slack Java SDK, Spring Kafka `KafkaTemplate`, Spring `RestClient`, JDK `java.net.http`, Jackson 3.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
