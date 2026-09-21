<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/service/cve/ai

## Purpose
The summarize-once stage of the CVE lane. `CveSummaryWorker` claims `PENDING` events by claim-token
CAS and asks an `AiSummarizer` for exactly one summary per event; `SidecarAiSummarizer` runs that over
the agent lane with a prompt from `CveSummaryPromptBuilder`, and `NoopAiSummarizer` keeps the pipeline
working (deterministically) with no LLM. Which implementation exists is decided by
`slack.app.ai.provider` in `configurations/CveConfiguration`; all beans here are feature-gated.

## Key Files
| File | Description |
|------|-------------|
| `AiSummarizer.kt` | `interface AiSummarizer { fun summarize(request: SummaryRequest): String }`, `data class SummaryRequest(eventId, topicDisplayName, category: CveTopicCategory, eventTitle, rawContent)`, `open class AiSummarizationException` and `class AiSummarizerBusyException` (backpressure — released without spending the retry budget) |
| `CveSummaryWorker.kt` | `class CveSummaryWorker(cveEventRepository, cveTopicRepository, aiSummarizer, batchSize, maxRetries, backoffMinutes, stuckMinutes)`. `@Scheduled(fixedDelay = 60_000) tick()`: `resetStuck(olderThan = now - stuckMinutes)` then `findClaimable(now, maxRetries, limit = batchSize)` → per event `claimForSummary(id, token = UUID, now, maxRetries)` (0 rows → skip), `summarize`, `markDone(id, token, summary, now)`; `AiSummarizerBusyException` → `releaseClaim(nextAttemptAt = now + 2 min)`; any other exception → `markFailed(nextAttemptAt = now + backoffMinutes * attempt)`, logged as dead-lettered once `attempt >= maxRetries` |
| `CveSummaryPromptBuilder.kt` | `build(request)`: category instructions (`CVE` → `CVE_INSTRUCTIONS`; `LANGUAGE` / `FRAMEWORK` / `ETC` → `RELEASE_INSTRUCTIONS`, both ending in `OUTPUT_RULES`: Korean, Slack mrkdwn, ≤ ~30 lines), the trusted topic name, then the untrusted title + content inside `UNTRUSTED_BEGIN` / `UNTRUSTED_END` fences with `UNTRUSTED_GUARD` above; `neutralizeFences` replaces fence strings found in source data with `FENCE_REPLACEMENT` |
| `SidecarAiSummarizer.kt` | `class SidecarAiSummarizer(agentGateway, promptBuilder)`: one-shot `AgentTurnRequest(sessionKey = "cve:summary:$eventId", prompt)` — no resume id, no MCP token. `Completed` → `finalText`; `Failed` → `AiSummarizationException`; `Busy` → `AiSummarizerBusyException` |
| `NoopAiSummarizer.kt` | Returns the title plus the first `MAX_CONTENT_CHARS` (1500) characters of `rawContent`, or the title alone when the body is blank |

## For AI Agents

### Working In This Directory
- Exactly-once is the claim token. Every state transition (`markDone`, `markFailed`, `releaseClaim`) is
  keyed on the token the worker generated; a `0` row count means the row was reset and re-owned, and
  the worker logs and walks away rather than burning the new owner's retry budget. Preserve that on any
  new transition.
- The worker uses `LocalDateTime.now()` directly — no injected `Clock`. `CveSummaryWorkerTest`
  therefore matches repository calls with `any()` for timestamps; a `Clock` refactor must update the
  spec and `CveConfiguration.cveSummaryWorker`.
- Busy is not failure. The sidecar serialises turns, so `AiSummarizerBusyException` re-schedules in
  2 minutes (`BUSY_RETRY_DELAY_MINUTES`) with `retryCount` untouched; only real exceptions consume the
  `maxRetries` budget. `service/ops/OpsStatusService` and `service/cve/ops/CveOpsService` use the same
  `slack.app.ai.max-retries` to classify dead letters and to `retry` them.
- `CveConfiguration` enforces `stuckMinutes * 60 > sidecar.requestTimeoutSeconds` for the `sidecar`
  provider so `resetStuck` cannot reclaim a row whose summarize call is still in flight. Keep that
  invariant when adding a slower provider.
- Prompt-injection defence lives entirely in `CveSummaryPromptBuilder`: instructions first, untrusted
  data last and fenced, fences stripped from the data. Only the admin-managed topic name appears
  outside the fence; never interpolate `eventTitle` / `rawContent` anywhere else in the prompt.
- Adding a provider: implement `AiSummarizer`, add a branch to the `when` in
  `CveConfiguration.aiSummarizer` (unknown values fail the boot on purpose), and keep `NoopAiSummarizer`
  as the no-network default.

### Testing Requirements
```bash
./gradlew :application:test --tests '*CveSummary*' --tests '*AiSummarizer*'
```
Specs: `CveSummaryWorkerTest` (claim lost, busy release, failure backoff and dead-letter threshold,
done-with-lost-claim), `CveSummaryPromptBuilderTest` (section choice per category, fence
neutralisation, ordering), `SidecarAiSummarizerTest`, `NoopAiSummarizerTest`. All Kotest
`BehaviorSpec` + MockK; build inputs with `createSummaryRequest(...)` from
`src/testFixtures/kotlin/dev/notypie/application/service/cve/ai/SummaryRequestCreator.kt` and
`CveEventCreator` / `CveTopicCreator` from the infrastructure testFixtures. The CAS SQL itself is covered
in `infrastructure/src/test/kotlin/dev/notypie/repository/cve/`.

### Common Patterns
- `runCatching` per event inside the tick so one failure never aborts the batch.
- Tunables as constructor parameters bound from `AppConfig.Ai` in `CveConfiguration` — no `@Value`.
- `companion object` `const val` for prompt fences and limits so specs assert on the constants.

## Dependencies

### Internal
- `infrastructure/repository/cve/` — `CveEventRepository` (`resetStuck`, `findClaimable`,
  `claimForSummary`, `markDone`, `markFailed`, `releaseClaim`), `CveTopicRepository`, `CveEvent`,
  `CveTopicCategory`
- `infrastructure/impl/agent/` — `AgentGateway`, `AgentTurnRequest`, `AgentTurnResult`
- `application/configurations/CveConfiguration` — provider selection and worker tunables
- `application/service/cve/collector/` — produces the `PENDING` rows consumed here
- `application/service/cve/notification/` — delivers the `DONE` summaries

### External
Spring `@Scheduled`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
