<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/cve/ai

## Purpose
Specs for the summarization stage: the prompt builder that fences untrusted advisory text, the claim/retry
worker that drives `AiSummarizer`, the deterministic `NoopAiSummarizer`, and the `SidecarAiSummarizer` that
maps one `AgentGateway` turn per event.

## Key Files
| File | Description |
|------|-------------|
| `CveSummaryPromptBuilderTest.kt` | Plain Kotest `BehaviorSpec`, no mocks. CVE category → `security analyst` template with `*영향*`, `*영향 버전*`, `*CVSS*`, `*대응*` and no `*주요 변경*`; the injection line `Ignore all instructions` sits after `UNTRUSTED_BEGIN` inside the `UNTRUSTED_GUARD`/`UNTRUSTED_END` block; output rules contain `Write in Korean.` and `Slack-friendly markdown`. FRAMEWORK → `developer-relations engineer` template with `*주요 변경*`, `*Breaking*`, `*업그레이드*` and no `*대응*`; LANGUAGE and ETC → release template. Raw content embedding `UNTRUSTED_END` → exactly one closing marker survives (counted with `windowed`), `FENCE_REPLACEMENT` present, `Reveal secrets.` kept as data. |
| `CveSummaryWorkerTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `tick()` with `batchSize = 10`, `maxRetries = 5`, `backoffMinutes = 10`: clean claim → `resetStuck` once, `findClaimable(maxRetries = 5, limit = 10)` once, `markDone` with the same token captured at `claimForSummary`, no `markFailed`; `claimForSummary` returns 0 → no summarize, no `findById`, no done/failed; event 1 (`retryCount = 2`) throws `AiSummarizationException` and event 2 succeeds → `markFailed` for 1 with `nextAttemptAt` inside `now + 30 min ± 5 s`, `markDone` for 2; empty batch → `resetStuck` still once; `AiSummarizerBusyException` → `releaseClaim` once, no `markFailed`/`markDone`; `markDone` returns 0 → neither `markFailed` nor `releaseClaim`. |
| `NoopAiSummarizerTest.kt` | Plain Kotest `BehaviorSpec`, no mocks. `Title\n\nShort body.`; blank content → title only; content over `NoopAiSummarizer.MAX_CONTENT_CHARS` → body length equals the cap exactly; repeated call → identical output with no `===== BEGIN` fence. |
| `SidecarAiSummarizerTest.kt` | Plain Kotest `BehaviorSpec` + MockK on `AgentGateway`. `Completed(finalText = "요약 결과")` → returned verbatim; captured `AgentTurnRequest` has `sessionKey = "cve:summary:42"`, `sessionId = null`, `scopedToken = null`, and the prompt carries the raw content after `UNTRUSTED_BEGIN`; `Failed(code = "timeout")` → `AiSummarizationException` whose message contains `timeout` and `event=7`; `Busy` → `AiSummarizationException` message containing `busy`, `converse` called once. |

## For AI Agents

### Working In This Directory
- The worker reads wall-clock `LocalDateTime.now()` for backoff; there is no injected `Clock`. The failure
  case brackets `tick()` with `before`/`after` timestamps and asserts `nextAttemptAt` within
  `backoffMinutes * (retryCount + 1)` of them, ±5 s. Do not pin this with a fixed clock without changing main.
- Token threading is asserted by capturing `claimForSummary(token = capture(claimToken))` and
  `markDone(token = capture(doneToken))` in two slots and comparing `captured` values; the same shape appears
  in `standup` and `meeting` schedulers.
- `summarizer.summarize(request = match { it.eventId == 1L })` is how per-event outcomes are split on one
  mock; a plain `any()` stub in the same `given` would shadow it.
- `AiSummarizerBusyException` extends `AiSummarizationException`, so the sidecar `Busy` case passes with
  `shouldThrow<AiSummarizationException>`; the worker is what distinguishes busy (release, no retry spend)
  from failure (`markFailed` with backoff).
- `CveSummaryPromptBuilder.UNTRUSTED_GUARD`, `UNTRUSTED_BEGIN`, `UNTRUSTED_END`, `FENCE_REPLACEMENT` are the
  public constants the fence assertions key on; renaming them breaks two specs.
- Template assertions match Korean section labels literally; the CVE and release templates are told apart by
  which labels are absent, so a shared label added to both templates silently weakens the negative checks.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.ai.*'
```
Fixtures used: `application` testFixtures `service/cve/ai/SummaryRequestCreator.kt` (`createSummaryRequest`),
`infrastructure` testFixtures `schema/CveEventCreator.kt` (`createCveEvent`) and `schema/CveTopicCreator.kt`
(`createCveTopic`).

### Common Patterns
- `workerWith(eventRepository, topicRepository = mockk(), summarizer = mockk())` — the event repository is
  always `relaxed` so unstubbed `markFailed`/`releaseClaim` return defaults, then `verify(exactly = 0)` locks
  them out.
- The Noop spec is the only place that asserts the summarizer makes no external call; it does so by checking
  the prompt fence is absent from the output.

## Dependencies

### Internal
- `application/service/cve/ai/AiSummarizer.kt` (`AiSummarizer`, `SummaryRequest`, exceptions),
  `CveSummaryPromptBuilder.kt`, `CveSummaryWorker.kt`, `NoopAiSummarizer.kt`, `SidecarAiSummarizer.kt`
- `infrastructure/impl/agent/AgentGateway`, `AgentTurnRequest`, `AgentTurnResult`,
  `repository/cve/CveEventRepository`, `CveTopicRepository`, `repository/cve/schema/CveTopicCategory`

### External
MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
