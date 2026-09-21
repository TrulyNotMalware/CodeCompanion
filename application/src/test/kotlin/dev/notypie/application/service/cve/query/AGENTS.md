<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/cve/query

## Purpose
Specs for `/latest`: `CveLatestQueryService` resolves the caller's subscriptions (or one explicit topic key),
reads recent summarized events, and DMs a capped digest; `CveQuerySlashServiceImpl` is the slash entry point
that gates on the feature flag and normalizes the optional topic-key argument.

## Key Files
| File | Description |
|------|-------------|
| `CveLatestQueryServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Disabled → no `stage`; no subscriptions and no key → DM contains `no CVE topic subscriptions`; two subscriptions → `findRecentDoneEvents(topicIds = listOf(11L, 12L), limit = 5)` rendered as `*Kotlin* — *v2.3.0*`, `New release.`, `*Java CVE* — *CVE-2026-1111*`; subscribed but nothing summarized → `No recent CVE updates`; five events with 500-char titles and 700-char summaries → markdown length `2900 + "\n…(truncated)".length` and contains `…(truncated)`; key `ghost` not in `findActiveTopics` → `` `ghost` is not available ``; key `kotlin` → read scoped to `topicIds = listOf(11L)` verified once. |
| `CveQuerySlashServiceImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Disabled → `commandExecutor.execute` never; enabled with `subCommands = listOf("kotlin")` → exactly one `execute<NoSubCommands>` whose command `is CveLatestSlashCommand`; `extractTopicKey`: empty → null, `listOf("  ", "spring")` → `spring`, `Kotlin` → `kotlin`. |

## For AI Agents

### Working In This Directory
- `markdown()` asserts the reply is a `ChannelMessage` targeted at `TEST_USER_ID` (a DM), the default user of
  `createCveLatestRequestEvent`; `cve/ops` targets the channel instead.
- With an explicit key the service consults `topicRepository.findActiveTopics()` and never the subscription
  repository, so those cases pass `subscriptionRepository = mockk()` strict and rely on it staying untouched.
- The truncation case shares the `2900` section cap with `cve/notification`; per-event caps are applied
  first, then the aggregate cap, which is why five oversized events still produce one marker.
- `extractTopicKey` is a companion function tested directly; the slash spec never inspects the executed
  command's key, so key normalization is covered only there.
- `eventPublisher` is a relaxed mock that is never verified in the query spec; add a `verify` if publish
  behaviour becomes part of the contract.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.query.*'
```
Fixtures used: `domain` testFixtures `command/CommandDomainInputCreator.kt` (`createCveLatestRequestEvent`),
`domain/Constants.kt` (`TEST_USER_ID`), `infrastructure` testFixtures `schema/CveTopicCreator.kt`
(`createCveTopic`) and `schema/CveEventCreator.kt` (`createCveRecentEvent`).

### Common Patterns
- `stagerCapturing(slot)` returns a stager whose `stage` captures the message and returns a relaxed
  `CommandEvent<EventPayload>`; the same helper shape is used in `cve/subscription`.
- The slash spec builds `InboundCommand` inline through `commandDataWith(subCommands)`; `headers` is an empty
  `LinkedMultiValueMap` and `payload` a relaxed `SlashCommandRequestBody`.

## Dependencies

### Internal
- `application/service/cve/query/CveLatestQueryService.kt`, `CveQuerySlashServiceImpl.kt`,
  `application/service/command/CommandExecutor`, `application/configurations/AppConfig.Cve`
- `domain/command/entity/slash/CveLatestSlashCommand`, `domain/command/NoSubCommands`,
  `domain/command/inbound/InboundCommand`, `domain/command/outbound/OutboundMessageStager`
- `infrastructure/repository/cve/CveSubscriptionRepository`, `CveTopicRepository`, `CveEventRepository`,
  `impl/command/slack/SlashCommandRequestBody`

### External
MockK, Kotest, Spring `LinkedMultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
