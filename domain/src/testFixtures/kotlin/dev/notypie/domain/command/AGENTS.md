<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src/testFixtures/kotlin/dev/notypie/domain/command

## Purpose
Builders for everything the command core consumes or emits, with no Slack type in sight: neutral
`InboundCommand` envelopes for slash / mention / interaction, `InboundInteraction` plus its field and action
helpers, `CommandBasicInfo`, the request events application services subscribe to, a `TestCommand` that
always succeeds, and fresh `IntentQueue` / `EventQueue` instances. Every context spec under
`domain/src/test/.../command/` and most application service specs start from here.

## Key Files
| File | Description |
|------|-------------|
| `CommandDomainInputCreator.kt` | `createCommandBasicInfo(appId, appToken, publisherId, channel, idempotencyKey)`; events `createCreateStandupRoutineEvent(name, creatorId, commandChannel, summaryChannel, questions, memberIds, weekdays, triggerLocalTime, cutoffMinutes, timezone)`, `createAgentConverseRequestEvent(prompt, threadId, requesterName, channelName)`, `createRoleManageRequestEvent(action = GRANT, targetUserId, role = DEVELOPER)`, `createCveSubscriptionRequestEvent(action = SUBSCRIBE, userId, topicKeys = ["cve-java"], type)`, `createCveOpsRequestEvent(action = LIST_TOPICS, topicKey, targetEventId)`, `createCveLatestRequestEvent(userId, topicKey)`; `createApprovalContents(idempotencyKey, commandDetailType, reason, publisherId, headLineText)` |
| `InboundCommandCreator.kt` | `createMentionInboundCommand(mentionedUserIds, commandTokens = ["help"], hasCommandStructure, appId, appToken, actorId, actorName, channel, channelName, teamId, message, thread)`, `createSlashInboundCommand(subCommands, triggerId, ...)`, `createInteractionInboundCommand(commandDetailType = APPROVAL_REQUEST, action = approveAction(), form, idempotencyKey, ...)`, `createInteractionResponseInboundCommand(interaction, ...)` |
| `InboundInteractionInputCreator.kt` | `createInboundInteraction(detailType = NOTHING, action = passiveAction(), form, actor, channelId, trigger, reply, message, idempotencyKey, routingExtras, submission)`; actions `approveAction`, `rejectAction`, `passiveAction`; fields `inboundField(kind, rawValue, isSelected, key)`, `applyButtonField()`, `rejectButtonField()`, `plainTextField(text)`, `datePickerField(date, format)`, `timePickerField(time, format)`, `multiUsersField(userName, maximumSequence)`; `SEPARATOR` |
| `MockEventBuilderCreator.kt` | `createIntentQueue(): IntentQueue` — a fresh `DefaultIntentQueue` (file name is historical; nothing here is mocked) |
| `TestCommandEventCreator.kt` | `TestPayload`, `TestCommandEvent(name, isInternal = true, ...)`, `INTERNAL_EVENT_NAME` / `EXTERNAL_EVENT_NAME`, `createInternalTestEvent()`, `createExternalTestEvent()`, `createDomainEventQueue()`, `EventQueue.flushQueue()` |
| `TestCommandFactory.kt` | `TestCommand(idempotencyKey, commandData, intentToProduce = null)` — `Command<NoSubCommands>` whose `TestContext` emits the optional intent and returns `CommandOutput.success` |
| `UnknownSubCommandDefinition.kt` | Enum implementing `SubCommandDefinition`: `UNKNOWN`, `TOO_MANY_ARGUMENTS_SUB_COMMAND` (`minRequiredArgs = Int.MAX_VALUE`) |

## For AI Agents

### Working In This Directory
- **Context specs** build input with `createInboundInteraction(detailType, action, form = listOf(...))` and
  a `createIntentQueue()`, then inspect the drained intents/outbounds. `AbstractCommandContextTest` wires
  this once; every `*ContextTest` under `domain/src/test/.../command/context/` and both parser specs extend
  it. `createCommandBasicInfo` is the default `responseBasicInfo` everywhere.
- **Field helpers** mirror what the production mapper produces: `applyButtonField()` / `rejectButtonField()`
  are `UNKNOWN`-kind selected fields (a button carries no input semantics), `plainTextField` is `TEXT`,
  the date/time pickers format with the pattern you pass, and `multiUsersField(name, n)` joins
  `name1,name2,…` with `SEPARATOR` when `n > 0`. Used by `MeetingContextTest`,
  `MeetingApprovalResponseContextTest`, `StandupFillContextTest`, `InboundInteractionTest`.
- **Envelopes**: `createMentionInboundCommand` (`CommandTest`, `InteractionCommandTest`,
  `ReplaceTextResponseCommandTest`, `AppMentionContextParserTest`, `DetailErrorAlertContextTest`;
  application `CommandExecutorTest`, `IdempotencyCreatorTest`), `createSlashInboundCommand`
  (`RequestMeetingCommandTest`, `IdempotencyCreatorTest`), `createInteractionResponseInboundCommand`
  (`CommandTest`, `InteractionCommandTest`, `InteractionContextParserTest`), and the convenience
  `createInteractionInboundCommand` that composes the previous two (`IdempotencyCreatorTest`).
- **Events** feed application service specs one-to-one: `createAgentConverseRequestEvent` →
  `AgentConverseServiceTest`, `createCreateStandupRoutineEvent` → `StandupRoutineSetupServiceTest`,
  `createRoleManageRequestEvent` → `RoleManagementServiceTest`, `createCveSubscriptionRequestEvent` →
  `CveSubscriptionServiceTest`, `createCveOpsRequestEvent` → `CveOpsServiceTest`,
  `createCveLatestRequestEvent` → `CveLatestQueryServiceTest`. Meeting-lane events live in `../meet/`. Each
  creator threads `idempotencyKey` into the nested `responseBasicInfo` — override the key, not the info,
  unless the spec is about mismatched keys.
- `createApprovalContents` is shared with infrastructure (`SlackApiEventConstructorTest`,
  `OutboundMessageCodecTest`, `ModalBlockBuilderTest`, `ModalTemplateBuilderTest`) and domain
  `ApprovalCallbackContextTest`; `headLineText = null` falls back to `"Approval Requests"`.
- `TestCommand` is the executor-level probe (application `CommandExecutorTest`): pass `intentToProduce` to
  check that one intent is drained and published. `TestContext` is `internal`; do not widen it.
- `TestCommandEvent` / `create{Internal,External}TestEvent` / `createDomainEventQueue` serve only
  `EventQueueTest`. `flushQueue()` and `UnknownSubCommandDefinition` (with `UNKNOWN_SUB_COMMAND_IDENTIFIER`)
  have **no consumer** — remove them or put them to use rather than adding parallel helpers.
- `createCreateStandupRoutineEvent` here (two questions, Mon/Tue) and the entity/DTO builders in
  `../standup/` (three questions, Mon–Fri) deliberately differ; do not "align" one to the other without
  checking the specs that depend on each default.

### Testing Requirements
No specs for the fixtures themselves. When a new `CommandContext`, `InboundFieldKind` or request event is
added to `domain/src/main`, add the matching `create*` / `*Field` helper here in the same change.

### Common Patterns
- Expression-bodied `create*` with every parameter defaulted to a `TEST_*` constant or a fixed literal;
  `UUID.randomUUID()` only for idempotency keys.
- Builder composition: `createInteractionInboundCommand` =
  `createInteractionResponseInboundCommand(createInboundInteraction(...))`; every `create*Event` embeds
  `createCommandBasicInfo(idempotencyKey = key)` in its payload.

## Dependencies

### Internal
- `domain/command/inbound/`, `intent/`, `entity/`, `entity/event/`, `entity/context/`, `dto/`,
  `dto/modals/`, `authorization/UserRole`
- `../Constants.kt`

### External
- `java.time`, `java.time.format.DateTimeFormatter`, `java.util.UUID`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
