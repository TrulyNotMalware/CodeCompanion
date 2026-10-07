<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-07 -->

# infrastructure/impl/cve

## Purpose
Pluggable feed sources for the CVE/release bot. `SourceAdapter` is the port the collector resolves by
`supports(sourceType)`; `GithubReleaseSourceAdapter` and `NvdCveSourceAdapter` fetch and normalize items
into `RawSourceEvent`s that `CveEventRepository.insertIgnore` persists idempotently.

## Key Files
| File | Description |
|------|-------------|
| `SourceAdapter.kt` | `data class RawSourceEvent(externalId, title, rawContent, publishedAt: LocalDateTime?)`; `interface SourceAdapter { supports(CveSourceType): Boolean; fetch(CveTopic): List<RawSourceEvent> }`; `internal fun JsonNode.stringOrNull()` (blank folds to null); `internal fun parseSourceTimestamp(String?)` (offset or offset-free ISO, null on failure) `SourceResponse(statusCode, headers, body)` carries the response headers (the GitHub adapter reads the rate-limit ones). |
| `GithubReleaseSourceAdapter.kt` | `(token, perPage, requestTimeout, apiBaseUrl = "https://api.github.com")`. `source_config` `{"repo": "owner/name"}` validated by `REPO_PATTERN`; `GET /repos/{repo}/releases?per_page=N` with `Accept: application/vnd.github+json` and a bearer only when `token` is non-blank. `externalId` = release `id`, title = `name` else `tag_name`, `rawContent` = `body`, `publishedAt` = `published_at` A 403/429 carrying `X-RateLimit-Remaining: 0` or `Retry-After` is logged as `GitHub rate limit exhausted …` with the reset instant and auth mode, any other non-2xx as `GitHub releases returned <status>`; both return an empty list, so the log line is what tells a rate-limited window from a quiet repo. `anonymousLimitWarning(topicCount, requestsPerTopicPerHour)` returns a boot warning when `token` is blank and the load reaches `ANONYMOUS_HOURLY_LIMIT` (60/hour per IP), else null; `CveConfiguration.githubReleaseSourceAdapter` logs it for the active GitHub topics at one request per window (at least the 5-minute collector tick). |
| `NvdCveSourceAdapter.kt` | `(apiKey, lookbackMinutes, requestTimeout, apiBaseUrl = NVD 2.0 URL, clock (the context bean; the window is computed from its instant in UTC), maxBodyBytes = 32 MiB, requestInterval = 6 s, onPageCapReached = {})`. Before each request it waits until `requestInterval` has passed since the previous one (a `ReentrantLock` serialises callers); 403/429/503 log as a refusal (rate limit or outage) and return empty. `source_config` `{"cpe": ...}` → `virtualMatchString`, else `{"keyword": ...}` → `keywordSearch`; window `lastModStartDate/lastModEndDate = [now - lookback, now]` in UTC formatted `yyyy-MM-dd'T'HH:mm:ss.SSS`; `apiKey` header only when non-blank. Title = `"<CVE-ID> <first line of the en description>"`, `rawContent` = description + `\n\nCVSS baseScore=… baseSeverity=…` (v3.1 > v3.0 > v2) Pages: `resultsPerPage = RESULTS_PER_PAGE` (1,000, half NVD's maximum, so a page stays well under `maxBodyBytes`) and `startIndex` advanced by each page's size until `totalResults`, at most `MAX_PAGES` (10) pages, each behind the same request slot; past the cap it logs a WARN and calls `onPageCapReached(topic)` (the application counts `cve.nvd.page.cap.reached{topic}`) because the next overlapping window re-reads the same first pages. A failed or refused later page keeps the pages already read; an interrupt (in the slot wait or the call) is rethrown with the flag restored, as before. |

## For AI Agents

### Working In This Directory
- **`fetch` must never throw**, with one exception: an interrupt — while `NvdCveSourceAdapter` waits for its
  pacing slot or while either adapter is inside `HttpClient.send` — re-sets the interrupt flag and rethrows
  `InterruptedException`, so `CveCollector.tick()` can stop on shutdown instead of sleeping through every
  remaining topic (before 2026-10-01 the send-time interrupt became `emptyList()` and cleared the flag). Missing/invalid `source_config`, a
  non-2xx status (rate limits included), invalid JSON, or a transport failure logs and returns `emptyList()`. `URI.create` on an unvalidated repo
  string would break that contract — that is why `REPO_PATTERN` exists.
- **Every request has one deadline over headers and body.** Both adapters read through
  `HttpClient.sendWithinDeadline` (`SourceAdapter.kt`): `HttpRequest.timeout()` covers only the wait for
  response headers, so a watchdog closes the body stream when `requestTimeout` runs out, and the body is read
  up to `maxBodyBytes` (NVD 32 MiB, GitHub 8 MiB). A source that stalls mid-body would otherwise block
  `CveCollector.tick()` and, on the shared scheduler, every other `@Scheduled` job. Both failures surface as
  `IOException`s and follow the `fetch` contract above (log, `emptyList()`).
- **Never log credentials.** Log lines carry only `topic.topicKey`, the status code, and the exception.
- **The NVD window is derived in UTC from `clock.instant()`.** NVD reads offset-free timestamps as UTC; a
  zoned wall clock would shift the window and silently empty every response. Inject a fixed `Clock` in
  specs. NVD rejects a bare-seconds timestamp — keep the millisecond pattern.
- **Blank strings fold to null in `stringOrNull()`** so `name ?: tag_name` works for tag-only GitHub
  releases (`"name": ""`).
- **Adding a source = adding an adapter bean**, not editing `CveCollector`. `CveSourceType.RSS` exists
  in the schema but has no adapter. `sourceType` has no default in the YAML topic definition, and
  `CveTopicBootstrap` refuses to start when a declared topic's `sourceType` has no supporting adapter.
  A stored row without an adapter is still warned about and skipped by the collector.
- **NVD calls are paced, per adapter instance (so per Pod).** Topics are fetched in a fixed order every tick,
  so without spacing the topics past NVD's quota (5 requests per rolling 30 s without a key, 50 with one) were
  refused on every tick and never collected; the lookback cannot heal a miss that repeats. The default 6 s is
  NVD's own guidance. Replicas behind one egress IP without a key share the quota, so two Pods collecting at
  the same time can still be refused; that miss is not systematic, and a later window's lookback re-reads it.
  A tick takes about `topics x 6 s` on one scheduler thread.
- Apart from the NVD pacing clock, adapters are stateless; overlapping windows are expected and deduplicated downstream by
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
