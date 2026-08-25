<!-- Parent: ../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

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
| `ModalTemplateBuilder.kt` | Composes complete modals for each form (meeting request, reschedule, add participant, standup setup/fill, CVE subscription, decline reason) |
| `ModalBlockBuilder.kt` | Block-level assembly used by the template builder |
| `ModalElementBuilder.kt` | Element-level widgets: text inputs, selects, date/time pickers, checkboxes |
| `SlackTemplateBuilder.kt` | Non-modal message templates |
| `InteractiveIds.kt` | Canonical `action_id` / `block_id` / `callback_id` constants |
| `dto/LayoutBlocks.kt` | Layout block DTOs |
| `dto/TimeScheduleAlertContents.kt` | Schedule-alert message contents |
| `dto/CheckBoxOptions.kt` | Checkbox option model |

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
Specs: `ModalTemplateBuilderTest`, `ModalBlockBuilderTest`, `ModalElementBuilderTest`. They assert the
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
