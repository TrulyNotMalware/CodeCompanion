<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-01 -->

# infrastructure/templates

## Purpose
Slack Block Kit construction. Turns domain-side content (`MessageContent`, `ModalForm`, approval and
schedule DTOs) into the JSON shapes the Slack Web API expects — messages, layout blocks, and `views.open`
modal payloads — behind small type-safe DSLs.

## Key Files
| File | Description |
|------|-------------|
| `SlackViewDsl.kt` | `modal { ... }` builder producing a `Map<String, Any>` view payload; preserves key order via `LinkedHashMap`. `plainTextInput(actionId, multiline, initialValue?, maxLength?)` appends `max_length` last when given |
| `LayoutBlocksDsl.kt` | DSL for Block Kit layout blocks |
| `ModalTemplateBuilder.kt` | Composes complete modals for each form (meeting request, reschedule, add participant, standup setup/fill, CVE subscription, decline reason); `approvalTemplate` names the publisher through `SlackUserProfileResolver`. `onlyTextTemplate` / `simpleTextResponseTemplate` (every `MessageContent.Text` reply) and the `errorNoticeTemplate` details render their body through `textSections`, so a long AI answer or digest becomes up to 50 / 48 / 47 sections instead of one rejected section. `simpleTextResponseTemplate` with a blank headline renders as `onlyTextTemplate` (no header, no divider): the renderer passes a channel reply's null headline as `""`, and Slack rejects an empty header. The `errorNoticeTemplate` reason field (`reason = <message>`) is cut to `SECTION_FIELD_MAX_LENGTH` (2,000) with `truncatePlainText`, since an exception message is unbounded (H11). `standupSummaryTemplate` renders a title section plus one section per member (`<@id>` then `• *question* answer` lines), each cut to the 2,900 budget — 31 blocks at `Routine.MAX_MEMBERS`, and past 49 members a "more members omitted" line keeps it at 50. `standupModalViewJson` gives every answer input the same `max_length`: the section budget minus a 32-character member line and each question line, divided by the question count (capped at 3,000, floored at `STANDUP_ANSWER_MIN_LENGTH` = 50 — questions heavy in `&<>` used to push it down to 1, review H10; past the floor the per-member section cut handles the overflow), so a member answering every question at the cap still fits one section. The decline-reason detail input carries `max_length = DECLINE_DETAIL_MAX_LENGTH` (255, the width of `meeting_participants.absent_reason_detail`). CVE topic-picker option text is cut to 75 characters (`truncatePlainText`); the value stays the full topic key (the 150-character-key limit is enforced at topic bootstrap). The picker lists at most the first 100 topics and logs WARN when there are more — the bootstrap and `cve topic activate` keep the active count at 100, but rows toggled in the DB can still pass it (V6) |
| `SlackUserProfileResolver.kt` | `resolve(userId): PublisherView(displayName, thumbnailUrl?)` — `users.profile.get?user={user}` via `RestRequester.safeGet` with `userId` as a URI template variable (never interpolated), cached per user (30 min TTL); any failure or `ok = false` degrades to `<@userId>` with no thumbnail and is negative-cached for `failureTtl` (60 s), so a user whose lookup keeps failing costs one Tier 4 call per minute, not one per render. At `maxEntries` (5 000) a new user first drops expired entries, then the oldest tenth by insertion time — never the whole cache; that eviction runs under a `ReentrantLock.tryLock`, so one thread sorts at a time and the others insert without it (the map can briefly exceed the cap by the number of concurrent stores). Concurrent misses for one user share the first caller's lookup through an in-flight `CompletableFuture` map (re-checked against the cache after winning the slot, released in `finally`, and an exception reaches every waiter). A failed lookup logs one WARN here; `RestClientRequester` logs it at DEBUG only |
| `ModalBlockBuilder.kt` | Block-level assembly used by the template builder. `simpleText` cuts text over the 3,000-character section cap (`truncateSectionText`) instead of letting one section fail the message; `textSections(text, isMarkDown, maxSections)` renders free text of any length as consecutive sections through `splitSectionText` |
| `SlackBlockLimits.kt` | `object SlackBlockLimits` — the Block Kit caps this module renders against (section text 3,000 with a 2,900 working budget, section field 2,000, 50 blocks per message, header 150, option text 75, 100 options, `plain_text_input` 3,000) and `TRUNCATION_MARKER`. `splitSectionText(text, maxSections, balanceCodeFences)` packs lines into chunks within the budget (hard-wrapping only a single over-long line, at a space when one is in the second half), keeps at most `maxSections` chunks and ends the last with the marker, and with `balanceCodeFences` closes and re-opens a triple-backtick block cut by a boundary — except when the next chunk starts on the block's own closing fence: that fence is dropped instead of re-opening an empty block, and a chunk that was only the fence disappears (H4); text that fits comes back unchanged. `truncateSectionText` (public, so the application-layer CVE bodies reuse it) / `truncatePlainText` cut one value. No cut splits a surrogate pair or an `&amp;`-style entity |
| `ModalElementBuilder.kt` | Element-level widgets: text inputs, selects, date/time pickers, checkboxes |
| `SlackMrkdwn.kt` | `String.escapeMrkdwn()`: delegates to the canonical `domain/common/escapeMarkup()` (public name kept for the Slack-side callers in this module and `application`); escapes `&`, `<`, `>` so user- or externally-supplied text interpolated into mrkdwn cannot become `<!channel>` or a disguised `<url\|label>` link — `verbatim` does not stop explicit `<…>` markup. Apply it at the interpolation site, not to whole template strings. `String.neutralizeBroadcastMentions()` is for AI output, which keeps its links, emphasis and `<@user>` mentions: it escapes the angle brackets of every `<!…>` sequence except `<!date^…>` formatting — the special mentions (`channel`, `here`, `everyone`, legacy `group`, `subteam^…`, with or without `\|label`, any case) and, as a harmless over-match, anything else written `<!…>` (an HTML comment, `<!DOCTYPE>`), which then shows as literal text. A `<!channel>` nested inside a `<!date^…\|…>` fallback is still defused |
| `SlackTemplateBuilder.kt` | Non-modal message templates |
| `InteractiveIds.kt` | Canonical `action_id` / `block_id` / `callback_id` constants |
| `dto/LayoutBlocks.kt` | Layout block DTOs |
| `dto/TimeScheduleAlertContents.kt` | Schedule-alert message contents |
| `dto/CheckBoxOptions.kt` | Checkbox option model |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `dto/` | `LayoutBlocks` / `InteractionLayoutBlock` / `InteractiveObject` wrappers, `CheckBoxOptions`, `TimeScheduleAlertContents` (see `dto/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Key order is behaviour, not style.** The DSL deliberately preserves the exact key order of the
  hand-rolled maps it replaced — Jackson serializes a `LinkedHashMap` in insertion order and several
  round-trip tests depend on it. Do not reorder builder calls or switch to an unordered map.
- **The DSL is scoped to this module's needs**, not a mirror of the whole Block Kit surface. Extend it
  with the helper a new template actually requires; resist speculative coverage.
- **Returning `Map<String, Any>` is intentional** — it keeps the bridge to the Slack SDK loose so callers
  can re-serialize or hand it straight to Jackson. Do not tighten it to SDK model types.
- **`action_id` / `block_id` / `callback_id` values are a wire contract** with the inbound side.
  `InteractiveIds` is the single source; `SlackInboundMapper` and the domain contexts route on these
  values, so renaming one without updating the parser silently breaks the interaction round trip.
- **Escape user text where it is interpolated.** `ModalTemplateBuilder` escapes the meeting title (list, decline
  modal, schedule notice), the decline detail, the approval subtitle and a resolved publisher name (not the
  `<@id>` fallback), and the standup routine name, questions and answers; the notice body is escaped in
  `SlackOutboundRenderer`. The bold markers and `<@id>` mentions a template adds stay outside the escape.
  Bodies of `onlyTextTemplate` / `simpleTextResponseTemplate` are caller-composed mrkdwn — escaping them is the
  caller's job (the AI answer is neutralized in `AgentConverseService`).
- **Render within Block Kit limits.** A payload over any cap in `SlackBlockLimits` is rejected whole and the
  relay treats that as permanent, so free text goes through `textSections` (or `truncateSectionText` for one
  section) and a new template that can grow with data needs a spec asserting its worst case stays under the caps.
- Interaction handling reads several form fields **positionally** (see `infrastructure/impl/AGENTS.md`),
  so the order in which a modal declares its inputs is part of the contract too.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.templates.*'
```
Specs: `ModalTemplateBuilderTest`, `ModalBlockBuilderTest`, `ModalElementBuilderTest`,
`SlackUserProfileResolverTest` (cache, negative cache, eviction, URI variable), `SlackBlockLimitsTest` (section
splitting, fence balancing, safe cuts) and `SlackMrkdwnTest` (escape, broadcast-mention neutralizing). The builder specs assert the
serialized shape, which is what makes key order and id constants regression-safe — when you add a
template, add a spec that pins its JSON, not just its non-nullness. A modal change should usually be
paired with a check on the matching context spec in `:domain` and mapper spec in `:infrastructure`.

### Common Patterns
- `@DslMarker`-annotated builder classes (`@SlackViewDsl`) so nested scopes cannot leak receivers.
- Builder functions accumulate into a `mutableMapOf`/`buildList` and expose a single `build()`.
- Constants over string literals for every Slack id.
- No I/O and no Slack SDK client here — these are pure payload builders.

## Dependencies

### Internal
- `domain/command/outbound/` — `MessageContent`, `ModalForm`
- `domain/command/dto/modals/` — `ApprovalContents`, `SelectionContents`, `TextInputContents`, `TimeScheduleInfo`
- `infrastructure/common/JsonMapper.kt` — shared `jsonMapper` for serialization

### External
Jackson 3 (serialization only). No Slack SDK client dependency.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
