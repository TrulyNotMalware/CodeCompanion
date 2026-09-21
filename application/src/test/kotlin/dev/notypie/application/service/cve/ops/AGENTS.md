<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/cve/ops

## Purpose
Spec for `CveOpsService`, the admin command handler behind `CveOpsRequestEvent`: list topics with event
counts, flip a topic's active flag, and re-queue dead-letter events for summarization. Replies are channel
messages to the command channel.

## Key Files
| File | Description |
|------|-------------|
| `CveOpsServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. LIST_TOPICS with a digest/active topic (7 events) and an immediate/inactive topic (no count row) → `CVE topics (2):`, `• *Kotlin* (\`kotlin\`) — digest, active, 7 event(s)`, `• *Java CVE* (\`cve-java\`) — immediate, inactive, 0 event(s)`; DEACTIVATE_TOPIC known → `setActive(topicKey = "kotlin", active = false)` and exact reply `Topic *Kotlin* (\`kotlin\`): active → inactive.`; ACTIVATE_TOPIC unknown → no `setActive`, `No CVE topic with key \`ghost\`.`; RETRY_ALL with `resetDeadLetters(maxRetries = 5)` returning 3 → `Re-queued 3 dead-letter event(s) for summarization.` and `publishEvent` once; RETRY_EVENT hit → `Re-queued event #42 for summarization.`; RETRY_EVENT miss → contains `Event #99 is not a dead-letter`; disabled → no `resetDeadLetters`, no `findAllTopics`, reply `The CVE feature is currently disabled.` still published. |

## For AI Agents

### Working In This Directory
- `markdown()` asserts the reply is a `ChannelMessage` targeted at `TEST_CHANNEL_ID`, the default channel of
  `createCveOpsRequestEvent`; the ops reply goes to the command channel, not to the admin as a DM.
- `maxRetries` reaches the service through `AppConfig.Ai(maxRetries = 5)`, and the dead-letter stubs are
  keyed on that exact value; `AppConfig.Cve(enabled)` toggles the disabled path.
- Unlike `cve/subscription`, the disabled path still stages and publishes a reply — the `verify(exactly = 1)`
  on `publishEvent` in that case is deliberate.
- `countEventsByTopic(topicIds = listOf(1L, 2L))` returns a partial `TopicEventCount` list; the missing topic
  renders as `0 event(s)`, so the ids in the stub must match `findAllTopics` order.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.ops.*'
```
Fixtures used: `domain` testFixtures `command/CommandDomainInputCreator.kt` (`createCveOpsRequestEvent`),
`domain/Constants.kt` (`TEST_CHANNEL_ID`), `infrastructure` testFixtures `schema/CveTopicCreator.kt`
(`createCveTopic`).

### Common Patterns
- Exact-string replies use `shouldBe`; the list and not-a-dead-letter replies use `shouldContain` because
  they carry trailing guidance text.
- Topic repositories are `relaxed` only in cases that call `setActive`; the read-only cases keep strict mocks.

## Dependencies

### Internal
- `application/service/cve/ops/CveOpsService.kt`, `application/configurations/AppConfig.Cve`, `AppConfig.Ai`
- `domain/command/entity/event/CveOpsAction`, `domain/command/outbound/OutboundMessageStager`
- `infrastructure/repository/cve/CveTopicRepository`, `CveEventRepository`, `TopicEventCount`,
  `repository/cve/schema/CveDeliveryMode`

### External
MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
