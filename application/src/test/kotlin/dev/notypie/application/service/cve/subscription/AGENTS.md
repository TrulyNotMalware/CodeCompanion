<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/cve/subscription

## Purpose
Specs for the subscribe/unsubscribe/list lane: `CveSubscriptionService` consumes the domain
`CveSubscriptionRequestEvent` and replies with a DM, and `CveSubscriptionSlashServiceImpl` is the slash entry
point that decides between opening a modal through `CommandExecutor` and staging an informative ephemeral.

## Key Files
| File | Description |
|------|-------------|
| `CveSubscriptionServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `handleCveSubscription`: SUBSCRIBE with keys `kotlin` + `ghost` against one active topic → `subscribe(userId, topicIds = listOf(10L))`, DM contains `Subscribed to 1 topic(s): *Kotlin*.` and `Skipped unavailable topics: \`ghost\`.`, `publishEvent` once; UNSUBSCRIBE → `unsubscribe(topicIds = listOf(5L))`, DM `Unsubscribed from 1 topic(s): *Java CVE*.`; UNSUBSCRIBE with an unsubscribed key → `Skipped not subscribed topics: \`ghost\`.`; LIST with two → `You're subscribed to 2 topic(s):` plus `• *Java CVE* (\`cve-java\`)` lines; LIST empty → exact `You have no CVE topic subscriptions.`; feature disabled → no write, no stage (`isCaptured shouldBe false`), no publish. |
| `CveSubscriptionSlashServiceImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Disabled → no `execute`, no `stage`, no `findActiveTopics`; `/subscribe` with active topics → `commandExecutor.execute<SubCommandDefinition>` once and no ephemeral; `/subscribe` with no active topics → no execute, `OutboundMessage.Ephemeral` containing `no CVE topics available`, `publishEvent` once; `/unsubscribe` with no subscriptions → ephemeral containing `no CVE topic subscriptions to remove`; `/subscriptions` → execute once, no stage. |

## For AI Agents

### Working In This Directory
- The confirmation DM is an `OutboundMessage.ChannelMessage` whose `target.id` is the user id; the
  `dmMarkdown()` extension asserts that before unwrapping `MessageContent.Text.markdown`. Ephemerals in the
  slash spec are unwrapped with `shouldBeInstanceOf<OutboundMessage.Ephemeral>()` instead.
- `serviceWith(...)` returns `Pair<CveSubscriptionService, EventPublisher>` so cases can `verify` the
  relaxed publisher; the stager stub returns a relaxed `CommandEvent<EventPayload>`.
- Disabled here means silent: no DM and no publish. `cve/ops` sends a "feature disabled" reply instead — do
  not copy that expectation across.
- The slash spec builds `InboundCommand(kind = SLASH, payload = SlashInvocation(TriggerHandle("trig")))`
  inline and passes a `relaxed` `SlashCommandRequestBody` mock; the domain `InboundCommandCreator` fixture is
  not used.
- Repositories that must stay untouched are strict mocks, so an unexpected call fails as a MockK error
  rather than a missing `verify`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.subscription.*'
```
Fixtures used: `domain` testFixtures `command/CommandDomainInputCreator.kt`
(`createCveSubscriptionRequestEvent`), `infrastructure` testFixtures `schema/CveTopicCreator.kt`
(`createCveTopic`).

### Common Patterns
- Resolution goes key → active topic id; unknown keys are reported in the DM but never reach the
  repository, so every write `verify` lists the exact `topicIds` list.
- `stagerCapturing(slot)` / `serviceWith(stagedMessage = slot)` are the two spellings of the same stager
  capture; both return the relaxed event stub.

## Dependencies

### Internal
- `application/service/cve/subscription/CveSubscriptionService.kt`, `CveSubscriptionSlashServiceImpl.kt`,
  `application/service/command/CommandExecutor`, `application/configurations/AppConfig.Cve`
- `domain/command/entity/event/CveSubscriptionAction`, `EventPublisher`,
  `domain/command/inbound/InboundCommand`, `domain/command/outbound/OutboundMessageStager`
- `infrastructure/repository/cve/CveSubscriptionRepository`, `CveTopicRepository`,
  `impl/command/slack/SlashCommandRequestBody`

### External
MockK, Kotest, Spring `LinkedMultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
