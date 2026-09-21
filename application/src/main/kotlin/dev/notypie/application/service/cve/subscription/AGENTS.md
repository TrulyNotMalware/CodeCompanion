<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/service/cve/subscription

## Purpose
Per-user CVE topic subscriptions. `CveSubscriptionSlashServiceImpl` turns `/subscribe`, `/unsubscribe`
and `/subscriptions` into slash commands for `CommandExecutor` (the modal contexts live in `domain`);
`CveSubscriptionService` is the `@EventListener` that applies the resulting
`CveSubscriptionRequestEvent` to `cve_subscription` and confirms in a DM. Both are plain `@Service`
beans that exist even when the CVE feature is off and fail closed on `appConfig.cve.enabled`.

## Key Files
| File | Description |
|------|-------------|
| `CveSubscriptionSlashService.kt` | Interface: `handleSubscribe`, `handleUnsubscribe`, `handleSubscriptions`, each `(headers, payload: SlashCommandRequestBody, commandData: InboundCommand)`. Called from `controllers/SlashCommandController` and `socket/SocketModeReceiver` |
| `CveSubscriptionSlashServiceImpl.kt` | `@Transactional` per method; feature off → `log.warn` and return. `/subscribe`: `findActiveTopics()`; empty → `publishInfo("There are no CVE topics available to subscribe to yet.")`, else `CveSubscribeSlashCommand(topics = TopicOption(key = topicKey, label = displayName))`. `/unsubscribe`: `findSubscribedTopics(actorId)`; empty → info ephemeral, else `CveUnsubscribeSlashCommand`. `/subscriptions`: always `CveSubscriptionsSlashCommand`. `publishInfo` stages an `OutboundMessage.Ephemeral` (recipient `null`) through `OutboundMessageStager` and `EventPublisher.publishOne` |
| `CveSubscriptionService.kt` | `@Transactional @EventListener handleCveSubscription(event: CveSubscriptionRequestEvent)`. `SUBSCRIBE`: resolves requested keys against `findActiveTopics()`, `subscribe(userId, topicIds = known)`, reply "Subscribed to N topic(s): *Name*." plus "Skipped unavailable topics: `key`."; `UNSUBSCRIBE`: resolves against `findSubscribedTopics`, "Unsubscribed from …" plus "Skipped not subscribed topics: …"; `LIST`: "You're subscribed to N topic(s):" with `• *Name* (`key`)` lines, or "You have no CVE topic subscriptions.". The reply is a `ChannelMessage` to `ConversationTarget(id = userId)` with `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)` — a DM without `conversations.open`; headline `CodeCompanion — CVE subscriptions` |

## For AI Agents

### Working In This Directory
- Two gates on purpose. The slash layer refuses early so no modal opens; the listener re-checks because
  a modal submission can land after a runtime toggle-off. Disabled at the listener means nothing is
  written, staged or published — no reply either (contrast `../ops/CveOpsService`, which replies).
- Unknown keys are reported, never rejected: `subscribe`/`unsubscribe` write only the resolved ids and
  name the rest in a "Skipped …" suffix. `requested.distinct()` dedups repeated keys before lookup.
- Topics are queried here, not in `domain`: the slash service passes `TopicOption(key, label)` into the
  command so the modal context stays persistence-blind. A deactivated topic disappears from the
  `/subscribe` picker (`findActiveTopics`) but stays removable via `/unsubscribe`
  (`findSubscribedTopics` is not filtered by `active`).
- `checkNotNull(outboundStager.stage(...))` in the listener: a `null` stage throws and rolls the
  subscription write back with it. The slash-side `publishInfo` uses `?.let` because nothing was
  written yet.
- Reply strings are asserted verbatim by the specs; change text and spec in the same commit.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.subscription.*'
```
`CveSubscriptionServiceTest` and `CveSubscriptionSlashServiceImplTest` (Kotest `BehaviorSpec`, MockK,
no Spring context). Build events with `createCveSubscriptionRequestEvent(action, userId, topicKeys)`
from the `domain` testFixtures and topics with `createCveTopic(id, topicKey, displayName)` from the
`infrastructure` testFixtures; assert on the captured `OutboundMessage`, not on Slack payloads.

### Common Patterns
- `serviceWith(...)` helper returning `Pair<Service, EventPublisher>` so cases can `verify` publish
  counts (same shape as `service/command/RoleManagementService`).
- `associateBy { it.topicKey }` then `mapNotNull` / `filter { it !in map }` to split known from unknown.
- `IdempotencyCreator.create(data = commandData)` for every slash-originated command.

## Dependencies

### Internal
- `application/service/command/CommandExecutor`, `application/common/IdempotencyCreator`,
  `application/configurations/AppConfig.Cve.enabled`
- `domain/command/entity/slash/` — `CveSubscribeSlashCommand`, `CveUnsubscribeSlashCommand`,
  `CveSubscriptionsSlashCommand`; `domain/command/entity/event/` — `CveSubscriptionRequestEvent`,
  `CveSubscriptionAction`, `publishOne`; `domain/command/outbound/` — `OutboundMessage`,
  `MessageContent.Text`, `TopicOption`, `OutboundMessageStager`
- `infrastructure/repository/cve/` — `CveTopicRepository`, `CveSubscriptionRepository`, `CveTopic`
- `infrastructure/impl/command/slack/SlashCommandRequestBody`

### External
Spring `@Service` / `@Transactional` / `@EventListener`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
