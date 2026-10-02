<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-02 -->

# infrastructure/templates

## Purpose
Slack Block Kit construction. Turns domain-side content (`MessageContent`, `ModalForm`, approval and
schedule DTOs) into the JSON shapes the Slack Web API expects — messages, layout blocks, and `views.open`
modal payloads — behind small type-safe DSLs.

## Key Files
| File | Description |
|------|-------------|
| `SlackViewDsl.kt` | `modal { ... }` builder producing a `Map<String, Any>` view payload; preserves key order via `LinkedHashMap` |
| `LayoutBlocksDsl.kt` | DSL for Block Kit layout blocks |
| `ModalTemplateBuilder.kt` | Composes complete modals for each form (meeting request, reschedule, add participant, standup setup/fill, CVE subscription, decline reason); the CVE topic picker (subscribe / unsubscribe) offers at most `MAX_OPTIONS` (100) topics with labels cut to 75 characters by `truncatePlainText` (keys unchanged) and logs a WARN when it drops topics, since `views.open` rejects the whole modal for every user past either limit; `approvalTemplate` names the publisher through `SlackUserProfileResolver` and escapes the profile name (the `<@id>` fallback stays a mention) and the subtitle. `standupSummaryTemplate` renders a title section plus one section per member, each cut to `SLACK_SECTION_TEXT_MAX_CHARS` (3,000, Slack's section limit), at most `STANDUP_SUMMARY_MAX_MEMBER_SECTIONS` (48) members and an "…and N more members" section, so the message stays within Slack's 50 blocks; it used to be one section, which Slack rejects as `invalid_blocks` past 3,000 characters |
| `SlackUserProfileResolver.kt` | `resolve(userId): PublisherView(displayName, thumbnailUrl?)` — `users.profile.get?user={user}` via `RestRequester.safeGet` with `userId` as a URI template variable (never interpolated), cached per user (30 min TTL); any failure or `ok = false` degrades to `<@userId>` with no thumbnail and is negative-cached for `failureTtl` (60 s), so a user whose lookup keeps failing costs one Tier 4 call per minute, not one per render. At `maxEntries` (5 000) a new user first drops expired entries, then the oldest tenth by insertion time — never the whole cache Concurrent misses for one user share the first caller's lookup through an in-flight `CompletableFuture` map (the cache is re-checked after winning the slot, the slot is released in `finally`, and an exception reaches every waiter). Eviction runs under `ReentrantLock.tryLock`, so one thread sorts at a time and the others insert without it (the map can briefly exceed the cap by the number of concurrent stores). |
| `ModalBlockBuilder.kt` | Block-level assembly used by the template builder. `simpleText` cuts text over 3,000 characters (`truncateSectionText`) instead of letting Slack reject the message. `textSections(text, isMarkDown)` first cuts the body to `MESSAGE_BODY_BUDGET` (marker, fences closed), then splits it into sections within the 2,900 budget; at that body size a message's header plus sections stays under `MESSAGE_TEXT_BUDGET`. `onlyTextTemplate` and `simpleTextResponseTemplate` (every `MessageContent.Text`) render through it, and a blank headline renders no header or divider (Slack rejects an empty header; the renderer passes a missing headline as `""`) |
| `SlackBlockLimits.kt` | `object SlackBlockLimits`: Block Kit caps this module renders against (section text 3,000 with a 2,900 working budget, section field 2,000, 50 blocks, header 150, option text 75, 100 options, `plain_text_input` 3,000) and `MESSAGE_TEXT_BUDGET` (12,000): total block text per message, kept under the undocumented ~13,200-character point where `chat.postMessage` returns `msg_blocks_too_long`. `MESSAGE_BODY_BUDGET` (11,000) is what one message's body may hold. `splitMessageText(text, maxMessages)` (public, for callers that post a long text as several messages) splits on line boundaries into bodies of that size with code fences balanced. `splitSectionText(text, maxSections, balanceCodeFences)` packs lines into chunks within the budget, keeps at most `maxSections` and ends the last with `TRUNCATION_MARKER`, and closes/re-opens a code block cut by a boundary. `truncateSectionText` (public) / `truncatePlainText` cut one value. No cut splits a surrogate pair or an `&amp;`-style entity |
| `SlackMrkdwn.kt` | `String.escapeMrkdwn()` delegates to `domain/common/escapeMarkup()`; apply it at the interpolation site of user- or externally-supplied text, not to whole template strings (`verbatim` does not stop explicit `<…>` markup). `String.neutralizeBroadcastMentions()` is for AI output, which keeps links, emphasis and `<@user>` mentions: it escapes the angle brackets of every `<!…>` sequence except `<!date^…>` |
| `ModalElementBuilder.kt` | Element-level widgets: text inputs, selects, date/time pickers, checkboxes |
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
- Interaction handling reads several form fields **positionally** (see `infrastructure/impl/AGENTS.md`),
  so the order in which a modal declares its inputs is part of the contract too.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.templates.*'
```
Specs: `ModalTemplateBuilderTest`, `ModalBlockBuilderTest`, `ModalElementBuilderTest`,
`SlackUserProfileResolverTest` (cache, negative cache, eviction, URI variable). The builder specs assert the
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
