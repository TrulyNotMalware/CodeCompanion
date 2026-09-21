<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/service/cve/ai

## Purpose
Builder for `SummaryRequest`, the input every `AiSummarizer` implementation and the prompt builder take.

## Key Files
| File | Description |
|------|-------------|
| `SummaryRequestCreator.kt` | `createSummaryRequest(eventId = 1L, topicDisplayName = "Java CVE", category = CVE, eventTitle = "Sample advisory", rawContent = "Raw advisory content.")` |

## For AI Agents

### Working In This Directory
- Consumers: `CveSummaryPromptBuilderTest`, `NoopAiSummarizerTest`, `SidecarAiSummarizerTest` under
  `application/src/test/.../service/cve/ai/`.
- `rawContent` is what ends up inside the prompt; prompt-shape specs override it with the text they assert
  on and leave the rest defaulted.
- `category` is infrastructure's `CveTopicCategory`, the same enum `../../../configurations/` uses.

### Testing Requirements
No spec for the fixture itself; the three summariser/prompt specs are the coverage.

### Common Patterns
- Single `create*` per request DTO, defaults chosen to read as obviously fake (`"Sample advisory"`).

## Dependencies

### Internal
- `dev.notypie.application.service.cve.ai.SummaryRequest` (main)

### External
- `dev.notypie.repository.cve.schema.CveTopicCategory` from `:infrastructure`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
