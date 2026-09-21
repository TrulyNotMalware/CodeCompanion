<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/service/cve/ops

## Purpose
Admin-only CVE operations reached through `@bot cve ...` mentions: list topics with event counts, flip a
topic's `active` flag, and re-queue dead-lettered events for summarization. `CveOpsService` is the
`@EventListener` for `CveOpsRequestEvent`; the mention parser in `domain` has already gated the actor as
`ADMIN` and validated the shape, so this class only applies the change and replies on the channel.

## Key Files
| File | Description |
|------|-------------|
| `CveOpsService.kt` | `@Service class CveOpsService(appConfig, cveTopicRepository, cveEventRepository, outboundStager, eventPublisher)`; `maxRetries = appConfig.ai.maxRetries`. `@Transactional @EventListener handleCveOps(event)`: feature off → "The CVE feature is currently disabled."; else `LIST_TOPICS` → "CVE topics (N):" with `• *Name* (`key`) — digest|immediate, active|inactive, N event(s)` from `findAllTopics()` + `countEventsByTopic` (missing count → 0); `ACTIVATE_TOPIC` / `DEACTIVATE_TOPIC` → `setActive(topicKey, active)` and "Topic *Name* (`key`): active → inactive." or "No CVE topic with key `key`."; `RETRY_ALL` → `resetDeadLetters(maxRetries)` and "Re-queued N dead-letter event(s) for summarization."; `RETRY_EVENT` → `resetDeadLetter(id, maxRetries)` and "Re-queued event #id …" or "Event #id is not a dead-letter (…)". Reply: `ChannelMessage` to `payload.responseBasicInfo.channel`, headline `CodeCompanion — CVE operations`, published via `publishOne` |

## For AI Agents

### Working In This Directory
- Disabled is a reply, not silence. Unlike `../subscription` and `../query`, an admin who typed a
  command always gets a message, and the repositories are never touched while disabled. Keep both
  halves: the spec asserts zero repository traffic and exactly one publish.
- `setActive` reads `findAllTopics()` (not `findActiveTopics()`) so an inactive topic can be found and
  re-activated; the old state in the reply comes from that read, the new one from the `active` argument.
- The retry commands are the only way to revive a `FAILED` event past `maxRetries`; `resetDeadLetter`
  returns `0` for unknown ids and for rows that are not dead-lettered, and the reply says so instead of
  throwing. `slack.app.ai.max-retries` must match what `../ai/CveSummaryWorker` and
  `service/ops/OpsStatusService` use, or "dead-letter" means different things in each place.
- `checkNotNull(payload.topicKey)` / `checkNotNull(payload.targetEventId)`: the parser guarantees these
  for the matching actions, so a `null` here is a parser bug and should surface.
- The staged reply is persisted by a `BEFORE_COMMIT` listener, so `@Transactional` keeps the flag
  update and the confirmation in one boundary (same reason as `service/command/RoleManagementService`).

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.ops.*'
```
`CveOpsServiceTest` (Kotest `BehaviorSpec`, MockK). Events from `createCveOpsRequestEvent(action,
topicKey, targetEventId)` in the `domain` testFixtures (reply target is `TEST_CHANNEL_ID`), topics from
`createCveTopic(...)` in the `infrastructure` testFixtures, counts as `TopicEventCount(topicId, count)`.

### Common Patterns
- One `when (payload.action)` producing the reply text, then a single stage + publish at the end.
- `companion object private const val` for the headline and the disabled message.
- `stateOf(topic)` helper so "active"/"inactive" wording has one source.

## Dependencies

### Internal
- `application/configurations/AppConfig` — `cve.enabled`, `ai.maxRetries`
- `domain/command/entity/event/` — `CveOpsRequestEvent`, `CveOpsAction`, `CveOpsPayload`, `publishOne`;
  `domain/command/outbound/` — `OutboundMessage.ChannelMessage`, `MessageContent.Text`,
  `OutboundMessageStager`
- `infrastructure/repository/cve/` — `CveTopicRepository` (`findAllTopics`, `setActive`),
  `CveEventRepository` (`countEventsByTopic`, `resetDeadLetters`, `resetDeadLetter`), `CveTopic`
- `application/service/cve/CveTopicBootstrap` — seeds the rows this service flips (it never re-syncs
  `active`)

### External
Spring `@Service` / `@Transactional` / `@EventListener`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
