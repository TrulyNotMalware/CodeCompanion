<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-26 -->

# infrastructure/templates/dto

## Purpose
Value types that ride through the template builders: the `LayoutBlocks` family pairs rendered Slack SDK
blocks/elements with the `States` the interaction parser expects back, `CheckBoxOptions` models a
checkbox/radio option, and `TimeScheduleAlertContents` carries the schedule-notice copy.

## Key Files
| File | Description |
|------|-------------|
| `LayoutBlocks.kt` | `LayoutBlocks(interactionStates: List<States> = [], template: List<LayoutBlock>)` — what every `SlackTemplateBuilder` method returns; `InteractionLayoutBlock(interactiveObjects: List<States>, layout: LayoutBlock)`; `InteractiveObject(state: States, element: BlockElement)` |
| `CheckBoxOptions.kt` | `CheckBoxOptions(text, description = "")`; consumed by `ModalElementBuilder.checkboxElements` / `radioButtonElements` and `ModalBlockBuilder.checkBoxesBlock` |
| `TimeScheduleAlertContents.kt` | `TimeScheduleAlertContents(title = "Time Schedule Notice", description = <three-line default copy>, startTime: LocalDateTime, host: String, rejectReasons = RejectReason.entries.map { it.showMessage }.toSet())`; input to `SlackTemplateBuilder.timeScheduleNoticeTemplate`, which only `ModalTemplateBuilderTest` calls |

## For AI Agents

### Working In This Directory
- **`template` is what goes on the wire; `interactionStates` is not.** The parser rebuilds `States` from
  Slack's `view.state.values` / `actions` at click time, so `interactionStates` is only inspected by the
  DSL (`LayoutBlocksDsl`) and by `ModalTemplateBuilderTest`. Do not rely on it for routing.
- **This package imports `impl/command/slack/States`** and the Slack SDK model (`LayoutBlock`,
  `BlockElement`). That is the only place `templates` touches the wire-DTO package — keep it that way.
- **`TimeScheduleAlertContents` is test-only today**: `timeScheduleNoticeTemplate` is called by no production
  path (the meeting notice goes through `simpleScheduleNoticeTemplate`, which takes the domain
  `TimeScheduleInfo`), so its default strings are not sent to Slack.
- `CheckBoxOptions.description` is optional and always rendered as `plain_text`; the option label is `mrkdwn`
  or `plain_text` by the `isMarkDown` parameter of `ModalElementBuilder.checkboxElements` /
  `ModalBlockBuilder.checkBoxesBlock` (default `true`; `radioButtonElements` is fixed `true`).
  The builders never validate Slack's 75-char option-text limit.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.templates.*'
```
`ModalBlockBuilderTest`, `ModalElementBuilderTest`, `ModalTemplateBuilderTest` pin the serialized shape of
blocks built from these DTOs. No spec targets the DTOs directly.

### Common Patterns
- Plain `data class`es with defaulted optional fields; constructed with named arguments.
- One file per concept, except `LayoutBlocks.kt`, which groups the three wrapper shapes that travel together.

## Dependencies

### Internal
- `impl/command/slack/States.kt`
- `domain/meet/entity/RejectReason`

### External
Slack SDK model (`com.slack.api.model.block.LayoutBlock`, `element.BlockElement`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
