<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-21 -->

# domain/command/inbound

## Purpose
The transport-neutral inbound envelope. An adapter (today `infrastructure/impl/command/SlackInboundMapper.kt`
and `SlackInteractionRequestParser.kt`) flattens a Slack slash command, app mention, or interaction into
`InboundCommand` + one `InboundPayload`; everything downstream routes on these types and never sees
a Slack payload.

## Key Files
| File | Description |
|------|-------------|
| `InboundCommand.kt` | `InboundKind` (`SLASH`, `MENTION`, `INTERACTION`); `sealed InboundPayload` with `SlashInvocation(trigger)` and `MentionInvocation(mentionedUserIds, commandTokens, hasCommandStructure, message?, thread?)`; `InboundCommand(appId, appToken, actorId, actorName, channel, channelName, teamId?, kind, subCommands, payload) : IdempotencyData` with `extractBasicInfo(idempotencyKey)` |
| `InboundInteraction.kt` | Value classes `TriggerHandle`, `ReplyHandle`, `MessageHandle`; `InboundActor(id)`; `InboundActionRole(triggersEvent)` = `APPROVE` / `REJECT` / `ACTIVATE` / `PASSIVE`; `InboundAction(role, isSelected)`; `InboundFieldKind(alwaysComplete)` = `TEXT` / `DATE` / `TIME` / `CHOICE` / `MULTI_CHOICE` / `USERS` / `CONVERSATION` / `TOGGLE` / `UNKNOWN`; `InboundField(key?, kind, isSelected, rawValue)`; `InboundForm(fields)` with `field` / `value` / `isSelected` / `first` / `all` / `firstValue`; `object InboundFieldKeys` (modal block-id constants); `sealed InboundSubmission` (`RescheduleMeeting`, `AddParticipant`, `DeclineReason`, `StandupAnswer`, `StandupSetup`, `CveSubscribe`, `CveUnsubscribe`); `InboundInteraction(detailType, actor, channelId, trigger, reply, message?, idempotencyKey: String, routingExtras, form, action, submission?) : InboundPayload`; extensions `isPrimary()`, `isCanceled()`, `isComplete()` |
| `SubmissionParseObserver.kt` | `fun interface SubmissionParseObserver` + `SubmissionIgnoreReason` (`MISSING_SUBMISSION` / `PARSE_REJECTED`): the observation port for submissions that fall open. Domain stays dependency-free (`NONE` default); the application binds it to Micrometer (`MeteredSubmissionParseObserver`). Expected per-flow defaults are not ignores |

## For AI Agents

### Working In This Directory
- `InboundCommand` is the `IdempotencyData` that `application/common/IdempotencyCreator` serializes and
  hashes into the idempotency `UUID`. Every constructor field participates — adding a volatile field
  (a receive timestamp, a request id) silently breaks replay de-duplication.
- `InboundInteraction` is itself an `InboundPayload` so it can ride in `InboundCommand.payload`;
  `entity/Command.executeCommand` branches on `is InboundInteraction` to call `handleInteraction`.
- `InboundFieldKeys` is the single source of truth for modal block ids, shared with the infrastructure
  writer (`infrastructure/templates/InteractiveIds.kt`) and reader (`SlackInboundMapper.kt`). Change
  all sides together: a drifted key does not fail, it makes `InboundForm.value(key)` return `""`.
- Routing is positional. A button value is `<idempotencyKey>,<DETAIL_TYPE>,<extra...>`, and the parser
  exposes the tail as `routingExtras`: `[meetingUid]` for `CANCEL_MEETING`, `MEETING_RESCHEDULE_REQUEST`,
  `MEETING_ADD_PARTICIPANT_REQUEST`; `[sessionUid, routineUid]` for `STANDUP_PROMPT`; `[meetingTitle]`
  for `MEETING_APPROVAL_REQUEST`. Modal submissions reuse `idempotencyKey` for the meeting uid from
  `private_metadata`, which is why it is a `String`, not a `UUID`.
- `submission` is non-null exactly when the detail type is a submission route: the mapper keys
  `buildSubmission` on the detail type, which also makes a detail-type/variant mismatch
  unrepresentable on the real path; block-action detail types carry `null`. Since Phase 11 the leaf
  contexts never touch this field: `entity/SubmissionRouting.kt` parses the variant
  into its `*Parsed` model before constructing the leaf, and a rejected or missing submission becomes
  `IgnoredSubmissionContext`, reported through `SubmissionParseObserver`. Add a new modal by adding an
  `InboundSubmission` variant here, its mapping in the infra mapper, a `*Parsed` model + leaf under
  `entity/context/form/`, and the `SubmissionRouter` branch (the exhaustive `when` forces it).
- `isComplete()` = primary action, selected, and every field either selected or of an `alwaysComplete`
  kind (`TEXT`, `TOGGLE`). `RequestMeetingContext` relies on it for "Please select all options".
- Names are deliberately neutral: `DomainLayeringGuardTest` fails on the identifiers `responseUrl` /
  `triggerId` anywhere in domain code. Use `ReplyHandle` / `TriggerHandle` / `MessageHandle`; the
  Slack origin may only be mentioned in comments.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.inbound.*'
```
`InboundFormTest` covers the `InboundForm` accessors and `isComplete()`; `InboundInteractionTest` covers
action roles and the extension predicates. Build inputs with `InboundCommandCreator` and
`InboundInteractionInputCreator` (`domain/src/testFixtures/kotlin/dev/notypie/domain/command/`).
The Slack-side mapping is pinned by `:infrastructure:test --tests '*SlackInboundMapperTest'
--tests '*SlackInteractionRequestParserTest'`.

### Common Patterns
- `@JvmInline value class` over `String` for every opaque transport handle.
- Sealed `InboundPayload` / `InboundSubmission` for exhaustive `when`; `data class` everywhere else.
- Raw `String` fields suffixed `Raw` (`meetingUidRaw`, `weekdaysRaw`) — parsing is the context's job.

## Dependencies

### Internal
- `command/dto/CommandBasicInfo`, `command/entity/CommandDetailType`, `common/IdempotencyData`

### External
`java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
