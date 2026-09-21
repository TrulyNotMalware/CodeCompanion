<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/cve

## Purpose
Specs for the CVE / release source adapters in main `impl/cve/`. The two adapter specs run against a real JDK
`HttpServer` on a loopback port and assert both the outgoing request (path, query, headers) and the mapping of
the JSON reply into `RawSourceEvent`s; the third spec pins the parsing helpers both adapters share. No Spring.

## Key Files
| File | Description |
|------|-------------|
| `SourceAdapterTest.kt` | Shared helpers. `JsonNode.stringOrNull()`: text passes through, numbers coerce to text (GitHub release ids), blank / JSON `null` / object / array all fold to `null` so `?:` fallback chains fire. `parseSourceTimestamp`: offset form (`…Z`, GitHub) keeps the local part, offset-free form (NVD) parses as `LocalDateTime`, malformed or `null` → `null` and never throws. Plain `BehaviorSpec`. |
| `GithubReleaseSourceAdapterTest.kt` | `GithubReleaseSourceAdapter(token, perPage, requestTimeout, apiBaseUrl).fetch(topic)`. Cases: two releases map to events (`id` → `externalId`, `name` → `title`, `body` → `rawContent`, `published_at`); request hits `/repos/<owner>/<name>/releases?per_page=…` with `Accept: application/vnd.github+json`; `Authorization: Bearer` only when a token is configured; `name` `null` or `""` falls back to `tag_name`; structurally malformed entries are skipped, valid ones survive; a `repo` value shaped like `owner/name/../../evil` returns empty **without sending a request**; missing `repo` key and non-2xx both return an empty list; `supports()` is true for `GITHUB_RELEASE` only. Plain `BehaviorSpec`. |
| `NvdCveSourceAdapterTest.kt` | `NvdCveSourceAdapter(apiKey, lookbackMinutes, requestTimeout, apiBaseUrl, clock).fetch(topic)`. Cases: a vulnerability maps to `externalId = cve.id`, `title = id + first English description line`, `rawContent = English description + CVSS baseScore/baseSeverity line`, `publishedAt`; `cpe` config → `virtualMatchString=`, `keyword` config → `keywordSearch=`, both with `lastModStartDate=`/`lastModEndDate=`; `apiKey` header only when configured; with a fixed clock in a +09:00 zone the window is still derived from the UTC instant (`10:00`–`12:00` for a 120-minute lookback at `12:00Z`); malformed entries skipped; neither `cpe` nor `keyword` → empty; non-2xx (403 rate limit) → empty rather than throwing; `supports()` is true for `NVD_CVE` only. Plain `BehaviorSpec`. |

## For AI Agents

### Working In This Directory
- Adapters return an empty list on every remote or config failure; they never throw into the collector.
  Every new failure mode needs an empty-list case here, not a `shouldThrow`.
- Topic inputs come from `testFixtures/.../schema/CveTopicCreator.kt` (`createCveTopic(sourceType =,
  sourceConfig =)`); the `sourceConfig` JSON strings in the spec are the only inline JSON and are intentionally
  tiny.
- The NVD window assertion is URL-encoded (`%3A`) and exact to the millisecond format; changing the
  timestamp formatter or the lookback default breaks it by design.
- `respond` / `capturedUri` / captured headers are spec-level `var`s reassigned per `given` — sequential
  execution is assumed, same as `impl/agent/`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.cve.*'
```
Loopback only; no outbound network. The adapters' `apiBaseUrl` constructor parameter exists so these specs
can point them at the stub — keep it injectable.

### Common Patterns
`HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)` with a `jsonResponse(status, body)` helper;
`afterSpec { server.stop(0) }`; an `adapter(...)` factory function so each `given` builds a fresh client with
the port of the running stub.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/cve/` — the adapters, `RawSourceEvent`, `stringOrNull`,
  `parseSourceTimestamp`
- `infrastructure/src/main/kotlin/dev/notypie/repository/cve/schema/CveSourceType`
- `infrastructure/src/testFixtures/kotlin/dev/notypie/schema/CveTopicCreator.kt`
- `infrastructure/src/main/kotlin/dev/notypie/common/jsonMapper` (helper spec)

### External
JDK `com.sun.net.httpserver`, Jackson 3 `JsonNode`, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
