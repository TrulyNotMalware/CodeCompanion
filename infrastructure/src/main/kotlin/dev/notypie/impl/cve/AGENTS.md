<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/impl/cve

## Purpose
Pluggable feed sources for the CVE/release bot. `SourceAdapter` is the port the collector resolves by
`supports(sourceType)`; `GithubReleaseSourceAdapter` and `NvdCveSourceAdapter` fetch and normalize items
into `RawSourceEvent`s that `CveEventRepository.insertIgnore` persists idempotently.

## Key Files
| File | Description |
|------|-------------|
| `SourceAdapter.kt` | `data class RawSourceEvent(externalId, title, rawContent, publishedAt: LocalDateTime?)`; `interface SourceAdapter { supports(CveSourceType): Boolean; fetch(CveTopic): List<RawSourceEvent> }`; `internal fun JsonNode.stringOrNull()` (blank folds to null); `internal fun parseSourceTimestamp(String?)` (offset or offset-free ISO, null on failure) |
| `GithubReleaseSourceAdapter.kt` | `(token, perPage, requestTimeout, apiBaseUrl = "https://api.github.com")`. `source_config` `{"repo": "owner/name"}` validated by `REPO_PATTERN`; `GET /repos/{repo}/releases?per_page=N` with `Accept: application/vnd.github+json` and a bearer only when `token` is non-blank. `externalId` = release `id`, title = `name` else `tag_name`, `rawContent` = `body`, `publishedAt` = `published_at` |
| `NvdCveSourceAdapter.kt` | `(apiKey, lookbackMinutes, requestTimeout, apiBaseUrl = NVD 2.0 URL, clock = UTC)`. `source_config` `{"cpe": ...}` → `virtualMatchString`, else `{"keyword": ...}` → `keywordSearch`; window `lastModStartDate/lastModEndDate = [now - lookback, now]` in UTC formatted `yyyy-MM-dd'T'HH:mm:ss.SSS`; `apiKey` header only when non-blank. Title = `"<CVE-ID> <first line of the en description>"`, `rawContent` = description + `\n\nCVSS baseScore=… baseSeverity=…` (v3.1 > v3.0 > v2) |

## For AI Agents

### Working In This Directory
- **`fetch` must never throw.** Missing/invalid `source_config`, a non-2xx status (rate limits included),
  invalid JSON, or a transport failure logs and returns `emptyList()`. `URI.create` on an unvalidated repo
  string would break that contract — that is why `REPO_PATTERN` exists.
- **Never log credentials.** Log lines carry only `topic.topicKey`, the status code, and the exception.
- **The NVD window is derived in UTC from `clock.instant()`.** NVD reads offset-free timestamps as UTC; a
  zoned wall clock would shift the window and silently empty every response. Inject a fixed `Clock` in
  specs. NVD rejects a bare-seconds timestamp — keep the millisecond pattern.
- **Blank strings fold to null in `stringOrNull()`** so `name ?: tag_name` works for tag-only GitHub
  releases (`"name": ""`).
- **Adding a source = adding an adapter bean**, not editing `CveCollector`. `CveSourceType.RSS` exists
  in the schema and in `AppConfig` but has no adapter — the collector warns and skips such topics.
- Adapters are stateless; overlapping windows are expected and deduplicated downstream by
  `unique(topic_id, external_id)`. Titles are truncated to 512 and raw content to 60 000 chars by
  `CveEventRepositoryImpl`, not here.
- Beans are created behind the CVE feature gate in `application/configurations/CveConfiguration.kt`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.cve.*'
```
`GithubReleaseSourceAdapterTest`, `NvdCveSourceAdapterTest` (local HTTP server, fixed clock) and
`SourceAdapterTest` (`stringOrNull`, `parseSourceTimestamp`). Cover the empty-list paths (bad config,
non-2xx, malformed JSON) for every new adapter — those are the contract.

### Common Patterns
- JDK `HttpClient` pinned to HTTP/1.1 with a 5 s connect timeout, mirroring `impl/agent`.
- `jsonMapper.readTree` + `stringOrNull()` for tolerant, allocation-light parsing of third-party JSON.
- `runCatching { ... }.getOrElse { log; return emptyList() }` at every I/O and parse boundary.

## Dependencies

### Internal
- `repository/cve` — `CveTopic`, `schema/CveSourceType`
- `common/JsonMapper.kt` — `jsonMapper`

### External
JDK `java.net.http`, Jackson 3 `JsonNode`, `kotlin-logging`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
