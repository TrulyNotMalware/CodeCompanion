<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/command/inbound (test)

## Purpose
Specs for the neutral inbound envelope: how a `InboundForm` exposes its fields and how an
`InboundInteraction` decides whether it is complete, primary, or canceled. These rules gate whether a
context runs at all, so they are pinned separately from the contexts.

## Key Files
| File | Description |
|------|-------------|
| `InboundFormTest.kt` | `InboundForm` accessors: `all(kind)` preserves inbound positional order (two `TEXT` fields → title then reason; two `TIME` fields → start then end), `firstValue(kind)`, `value(key)` / `isSelected(key)` with empty-string / `false` for missing keys; documents that standup answers arriving out of block-id order must be re-sorted by the `standup_q_<index>` key by the caller. `BehaviorSpec`; builds `InboundField` inline rather than via `inboundField` |
| `InboundInteractionTest.kt` | `isComplete()`: primary action selected and every field selected → `true`; an unselected field → `false`; non-primary or unselected action → `false`; unselected `TOGGLE` or `TEXT` fields still count as complete. `isPrimary()`: `APPROVE` and `REJECT` are both primary (a reject still triggers its own event), `PASSIVE` is not. `isCanceled()`: `REJECT` only. `BehaviorSpec`; uses `createInboundInteraction`, `inboundField`, `approveAction`, `rejectAction`, `passiveAction` |

## For AI Agents

### Working In This Directory
- A new `InboundFieldKind` needs a decision on whether it is "always complete" (like `TOGGLE` / `TEXT`)
  and a case in `InboundInteractionTest` recording it.
- The out-of-order standup case in `InboundFormTest` is intentionally a documentation test — the
  form does **not** sort; the mapper in infrastructure does. Do not "fix" it by sorting in `all()`.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.inbound.*'
```

### Common Patterns
- `createInboundInteraction(detailType = ..., action = approveAction(isSelected = ...), form = listOf(inboundField(kind = ..., isSelected = ...)))`
  with one flag varied per `when`.

## Dependencies

### Internal
- `dev.notypie.domain.command.inbound.{InboundForm, InboundField, InboundFieldKind, InboundInteraction}`,
  `command.entity.CommandDetailType`.
- `testFixtures` — `command/InboundInteractionInputCreator.kt`.

### External
- Kotest (`BehaviorSpec`, `shouldBe`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
