<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/service/cve/query

## Purpose
The read-only `/latest [topic-key]` slash command. `CveQuerySlashServiceImpl` builds a
`CveLatestSlashCommand` for `CommandExecutor`; `CveLatestQueryService` is the `@EventListener` that
answers the resulting `CveLatestRequestEvent` with a DM of the newest `DONE`-summarized events. It is a
pure DB read — the AI summary was produced once by `../ai/CveSummaryWorker`, never here.

## Key Files
| File | Description |
|------|-------------|
| `CveQuerySlashService.kt` | Interface: `handleLatest(headers, payload: SlashCommandRequestBody, commandData: InboundCommand)`. Called from `controllers/SlashCommandController` and `socket/SocketModeReceiver` |
| `CveQuerySlashServiceImpl.kt` | `@Transactional handleLatest`: feature off → `log.warn` and return; else `CveLatestSlashCommand(idempotencyKey, commandData, topicKey = extractTopicKey(commandData.subCommands))`. `internal fun extractTopicKey(subCommands)` in the companion: first non-blank argument, lower-cased; `null` when there is none |
| `CveLatestQueryService.kt` | `@Transactional @EventListener handleCveLatest(event)`: feature off → return silently. No topic key → `findSubscribedTopics(userId)`; empty → "You have no CVE topic subscriptions. Use `/subscribe` to pick topics first."; else read across all subscribed ids. With a key → `findActiveTopics().firstOrNull { it.topicKey == key }` or "Topic `key` is not available.". `findRecentDoneEvents(topicIds, limit = LATEST_LIMIT (5))`; empty → "No recent CVE updates …". Each event renders as `*Topic* — *Title*` + summary cut at `SUMMARY_MAX_LENGTH` (700); the joined body is cut at `BODY_MAX_LENGTH` (2 900) with `…(truncated)`. DM via `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)`, headline `CodeCompanion — latest CVE updates` |

## For AI Agents

### Working In This Directory
- Cap the aggregate, not just each summary. The whole body lands in one Slack `section` block and Slack
  rejects mrkdwn over 3 000 characters (`invalid_blocks`); 5 × 700 already exceeds that, so
  `BODY_MAX_LENGTH` is what makes the DM postable. The same rule lives in
  `../notification/CveNotificationDispatcher`.
- Key normalisation happens once, in `extractTopicKey` (`lowercase()`), because topic keys are
  lower-case by convention; `/latest Kotlin` must find `kotlin`. Keep it `internal` — the spec calls it
  directly.
- A topic argument only matches **active** topics, so a deactivated topic reads as "not available" even
  for a user still subscribed to it; the no-argument path reads subscribed topics regardless of
  `active`.
- `aiSummary` is nullable on `CveRecentEvent` (`orEmpty()`); `findRecentDoneEvents` should only return
  `DONE` rows, but do not assume a non-null summary.
- Two feature gates, same reasoning as `../subscription`: slash layer refuses early, listener re-checks.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.query.*'
```
`CveLatestQueryServiceTest` and `CveQuerySlashServiceImplTest` (Kotest `BehaviorSpec`, MockK). Events
from `createCveLatestRequestEvent(topicKey)` (`domain` testFixtures; target is `TEST_USER_ID`), rows from
`createCveTopic(...)` and `createCveRecentEvent(topicDisplayName, title, aiSummary)` (`infrastructure`
testFixtures). The truncation case asserts `markdown.length == 2900 + "\n…(truncated)".length`.

### Common Patterns
- `stagerCapturing(slot)` helper returning a MockK `OutboundMessageStager` whose `stage` captures the
  message and returns a relaxed `CommandEvent`.
- Early-return rendering with a local `emptyMessage` chosen per branch.

## Dependencies

### Internal
- `application/service/command/CommandExecutor`, `application/common/IdempotencyCreator`,
  `application/configurations/AppConfig.Cve.enabled`
- `domain/command/entity/slash/CveLatestSlashCommand`; `domain/command/entity/event/` —
  `CveLatestRequestEvent`, `CveLatestPayload`, `publishOne`; `domain/command/dto/CommandBasicInfo`;
  `domain/command/outbound/`
- `infrastructure/repository/cve/` — `CveSubscriptionRepository`, `CveTopicRepository`,
  `CveEventRepository.findRecentDoneEvents`, `CveRecentEvent`
- `infrastructure/impl/command/slack/SlashCommandRequestBody`

### External
Spring `@Service` / `@Transactional` / `@EventListener`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
