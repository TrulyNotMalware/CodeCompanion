<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/entity/context

## Purpose
One `CommandContext` per user-visible behaviour. The two base classes define the execution contract;
the concrete classes here are the mention-driven and text-response contexts. Modal and button flows
live in `form/`.

## Key Files
| File | Description |
|------|-------------|
| `CommandContext.kt` | `internal abstract class CommandContext<T>(commandBasicInfo, tracking = true, subCommand, intents)`: lazy `commandType` / `commandDetailType` from the two abstract `parse*` methods; `runCommand()` defaults to `CommandOutput.empty()`; `createErrorResponse(errMessage[, results])` queues an error ephemeral then returns `fail` (or `results`); `addIntent` / `addOutbound` offer to the queue |
| `ReactionContext.kt` | `internal abstract class ReactionContext<T>`: `interactionSuccessResponse(replyHandle, mkdMessage[, results])` emits `ReplaceMessage`; `runCommand(commandDetailType)`; `handleInteraction(interaction)` defaults to a success reply. `internal abstract class ResponseContext(isOk)` makes `runCommand()` final and delegates to `runCommand(commandDetailType)` |
| `AgentChatContext.kt` | `@bot ask` / free text → `CommandIntent.AgentConverse` (`AGENT_CONVERSE`); blank prompt → error ephemeral `EMPTY_PROMPT_MESSAGE` |
| `StatusContext.kt` | `@bot status` → `CommandIntent.StatusReport` (`STATUS_REPORT`) |
| `RoleManagementContext.kt` | `grant` / `revoke` / `roles` — forwards the intent the parser built (`SIMPLE_TEXT`) |
| `CveOpsContext.kt` | `cve ...` admin mentions — forwards the intent the parser built (`SIMPLE_TEXT`) |
| `NoticeContext.kt` | `notice @u1 @u2 text` → `OutboundMessage.Notice(mentions, message)` (`SIMPLE_TEXT`) |
| `ApprovalFormContext.kt` | `approval` → `MessageContent.Form` with a hard-coded purpose select ("Pull Requests" / "Logs") (`APPROVAL_REQUEST`) |
| `RequestApprovalContext.kt` | `OutboundMessage.Approval` built from `commands.poll()` as the reason (`APPLY_REQUEST`); carries a `FIXME` about moving to a modal |
| `TextResponseContext.kt` | Channel message with headline "Simple Text Response" (`SIMPLE_TEXT`) — help, usage, permission-denied, "Command Not supported." |
| `EphemeralTextResponseContext.kt` | `ResponseContext`; ephemeral text, `isOk` selects `success` / `fail` |
| `ReplaceMessageContext.kt` | `ReplaceMessage` through a reply handle (`REPLACE_TEXT`); same behaviour from `runCommand` and `handleInteraction` |
| `DetailErrorAlertContext.kt` | Channel `MessageContent.ErrorNotice(className, message, details)` (`SIMPLE_TEXT`) |
| `EmptyContext.kt` | No-op (`NOTHING`); returns `CommandOutput.empty()` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `form/` | Button- and modal-driven contexts: meeting request/approve/decline/cancel/reschedule/add-participant, standup setup and fill, CVE subscribe/unsubscribe/latest (see `form/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Contract: implement `parseCommandType`, `parseCommandDetailType`, and either `runCommand()` (slash /
  mention) or `handleInteraction()` (`ReactionContext`, for button and modal payloads).
  `Command.executeCommand` throws `UNSUPPORTED_COMMAND_TYPE` if an interaction reaches a context that
  is not a `ReactionContext`.
- Contexts never call a repository, an HTTP client, or the agent backend. `StatusContext`,
  `RoleManagementContext`, `CveOpsContext` and `AgentChatContext` exist only to queue an intent; the
  listener that owns the resource does the work and replies. Keep new behaviour on that side of the line.
- `createErrorResponse` both queues an ephemeral and returns `fail` — the ephemeral is delivered even
  though `ok == false`. `recipient = null` on those ephemerals is deliberate (the addressee is resolved
  from `basicInfo` downstream; `chat.postEphemeral` needs a channel id, not a user id).
- `commandType` / `commandDetailType` are `by lazy`, which is what lets `RequestApprovalContext` and
  `ApprovalCallbackContext` read `commandDetailType` inside a property initializer.
- `tracking` on `CommandContext` is never read anywhere; do not build on it.
- All contexts are `internal`. They are reached through `parsers/`, `CommandDetailType.createContext`,
  or a slash `Command`; specs can see them because the test source set shares the module.
- `TextResponseContext` posts to the **channel**; use `EphemeralTextResponseContext` when only the
  actor should see the text.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.context.*'
```
Specs mirror file names under `domain/src/test/kotlin/dev/notypie/domain/command/context/`:
`AgentChatContextTest`, `ApprovalFormContextTest`, `DetailErrorAlertContextTest`, `EmptyContextTest`,
`EphemeralTextContextTest`, `NoticeContextTest`, `ReplaceMessageContextTest`,
`RequestApprovalContextTest`, `TextResponseContextTest`. Extend `AbstractCommandContextTest` (or
`AbstractReactionCommandContextTest`) and assert on the drained queue. `StatusContext`,
`RoleManagementContext` and `CveOpsContext` have no dedicated spec; they are covered through
`AppMentionContextParserTest`.

### Common Patterns
```kotlin
override fun runCommand(): CommandOutput {
    addOutbound(OutboundMessage.ChannelMessage(target = ConversationTarget(id = commandBasicInfo.channel), content = ...))
    return CommandOutput.success(basicInfo = commandBasicInfo, commandType = commandType, commandDetailType = commandDetailType)
}
```
- `CommandType.SIMPLE` for one-shot replies, `PIPELINE` for anything that queues an intent or opens a
  form.
- `SubCommand.empty()` default for contexts without a sub-command grammar.

## Dependencies

### Internal
- `command/SubCommandDefinition.kt`, `command/dto`, `command/dto/modals`, `command/dto/response`,
  `command/entity/CommandType`, `command/inbound`, `command/intent`, `command/outbound`

### External
`java.util` (`Queue`, `UUID`) only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
