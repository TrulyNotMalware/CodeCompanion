<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/dto/modals

## Purpose
Transport-neutral descriptions of interactive message content — approval prompts, select boxes, text
inputs, schedule blocks. Contexts put them inside `MessageContent` / `OutboundMessage.Approval`; the
infrastructure template builders turn them into Block Kit.

## Key Files
| File | Description |
|------|-------------|
| `ApprovalContents.kt` | `ApprovalContents(headLineText = "Approval Requests", type = SIMPLE_REQUEST_FORM, reason, subTitle = "", publisherId, approvalButtonName = "Approval", rejectButtonName = "Deny", idempotencyKey, commandDetailType, time = now())`; `ApprovalContentType` (`SIMPLE_REQUEST_FORM`, `FINAL_CONFIRM_FORM` — the latter unused) |
| `SelectionContents.kt` | `SelectionContents(title, explanation, placeholderText, contents: List<SelectBoxDetails>)`; `SelectBoxDetails(name, isMarkDown = false, value: Any)`; `MultiUserSelectContents(title, placeholderText)` — used only by infrastructure templates |
| `TextInputContents.kt` | `TextInputContents(title, placeholderText)` |
| `TimeScheduleInfo.kt` | `TimeScheduleInfo(scheduleName, startTime, endTime, timeZone = Asia/Seoul, timeFormatter = "yyyy-MM-dd HH:mm")`; `toString()` renders `"name\nstart ~ end"` |

## For AI Agents

### Working In This Directory
- `ApprovalContents.idempotencyKey` + `commandDetailType` become the button value that routes the
  click back to a context. `RequestMeetingContext.sendNotice` sets `MEETING_APPROVAL_REQUEST` and the
  comment there says "must match" — the value here, not the emitting context's own detail type, is what
  `SlackInteractionRequestParser` reads.
- `time` defaults to `LocalDateTime.now()` at construction, so two otherwise-identical instances are
  not equal. Pass it explicitly in specs and anywhere equality or idempotency matters.
- These DTOs travel inside `MessageContent.Form` / `Schedule` and `OutboundMessage.Approval`, which the
  outbox codec persists as JSON. `SelectBoxDetails.value: Any` is serialized as-is — keep it a `String`
  or an enum name; renaming any field here changes stored outbox rows.
- `TimeScheduleInfo.toString()` is user-facing text (`MessageContent.Schedule` renders it). The
  `java.util.TimeZone` default is `Asia/Seoul` and is currently informational only — the formatter
  does not apply it.
- Renderers: `infrastructure/templates/SlackTemplateBuilder.kt` and `ModalTemplateBuilder.kt`. Add a
  DTO here only when a new Block Kit element needs domain-side data; otherwise extend `MessageContent`.

### Testing Requirements
No domain spec targets these DTOs; they are built by the contexts that emit them:
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.context.ApprovalCallbackContextTest' \
  --tests 'dev.notypie.domain.command.context.RequestApprovalContextTest' \
  --tests 'dev.notypie.domain.command.context.ApprovalFormContextTest'
```
Rendering is pinned by `:infrastructure:test --tests '*ModalTemplateBuilderTest'`.

### Common Patterns
- `data class` with defaulted display strings so contexts only pass what varies.

## Dependencies

### Internal
- `command/entity/CommandDetailType`

### External
`java.time`, `java.util.UUID`, `java.util.TimeZone` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
