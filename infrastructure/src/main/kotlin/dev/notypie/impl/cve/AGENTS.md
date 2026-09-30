<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-30 -->

# infrastructure/impl/cve

## Purpose
Pluggable feed sources for the CVE/release bot. `SourceAdapter` is the port the collector resolves by
`supports(sourceType)`; `GithubReleaseSourceAdapter` and `NvdCveSourceAdapter` fetch and normalize items
into `RawSourceEvent`s that `CveEventRepository.insertIgnore` persists idempotently.

## Key Files
| File | Description |
|------|-------------|
| `SourceAdapter.kt` | `data class RawSourceEvent(externalId, title, rawContent, publishedAt: LocalDateTime?)`; `interface SourceAdapter { supports(CveSourceType): Boolean; fetch(CveTopic): List<RawSourceEvent> }`; `internal fun JsonNode.stringOrNull()` (blank folds to null); `internal fun parseSourceTimestamp(String?)` (offset or offset-free ISO, null on failure) |
| `GithubReleaseSourceAdapter.kt` | `(token, perPage, requestTimeout, apiBaseUrl = "https://api.github.com")`. `source_config` `{"repo": "owner/name"}` validated by `REPO_PATTERN`; `GET /repos/{repo}/releases?per_page=N` with `Accept: application/vnd.github+json` and a bearer only when `token` is non-blank. A 403/429 carrying `X-RateLimit-Remaining: 0` or `Retry-After` is logged as `GitHub rate limit exhausted …` with the reset instant and auth mode, any other non-2xx as `GitHub releases returned <status>`; both still return an empty list. `anonymousLimitWarning(topicCount, requestsPerTopicPerHour)` returns a boot warning when `token` is blank and the load reaches `ANONYMOUS_HOURLY_LIMIT` (60), else null; `CveConfiguration.githubReleaseSourceAdapter` logs it. `externalId` = release `id`, title = `name` else `tag_name`, `rawContent` = `body`, `publishedAt` = `published_at` |
| `NvdCveSourceAdapter.kt` | `(apiKey, lookbackMinutes, requestTimeout, apiBaseUrl = NVD 2.0 URL, clock = UTC, sleeper = Thread.sleep)`. Pages with `startIndex` until `totalResults` (or an empty page), at most `MAX_PAGES` (5) requests, pausing 6 s between pages without a key and 0.6 s with one; `source_config` `{"cpe": ...}` → `virtualMatchString`, else `{"keyword": ...}` → `keywordSearch`; window `lastModStartDate/lastModEndDate = [now - lookback, now]` in UTC formatted `yyyy-MM-dd'T'HH:mm:ss.SSS`; `apiKey` header only when non-blank. Title = `"<CVE-ID> <first line of the en description>"`, `rawContent` = description + `\n\nCVSS baseScore=… baseSeverity=…` (v3.1 > v3.0 > v2) |

## For AI Agents

### Working In This Directory
- **`fetch` must never throw.** Missing/invalid `source_config`, a non-2xx status (rate limits included),
  invalid JSON, or a transport failure logs and returns `emptyList()`. `URI.create` on an unvalidated repo
  string would break that contract — that is why `REPO_PATTERN` exists.
- **NVD paging respects the NVD rate limit** (5 requests / 30 s anonymous, 50 with a key): the adapter
  sleeps `sleeper(pagePause)` before every page after the first and caps a fetch at `MAX_PAGES`. A failed
  later page (transport, non-2xx, bad JSON) or an interrupted pause keeps the events already read — the
  next window's 120-minute lookback re-covers the rest — and an interrupt restores the thread's flag
  rather than throwing. Pacing *between topics* is still the collector's open item (review M23).
- **A rate-limited window must be tellable from a quiet repo in the logs** (V8, M23 family). Both return
  `emptyList()` by contract; only `logFailure`'s distinct `rate limit exhausted` line separates them. The
  prod default `GITHUB_TOKEN` is blank, and 5 topics polled every 5 minutes already reach the anonymous
  60/hour, which is why the boot check exists.
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
