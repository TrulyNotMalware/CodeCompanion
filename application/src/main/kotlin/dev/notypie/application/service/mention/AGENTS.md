<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# application/service/mention

## Purpose
Turns a Slack `app_mention` event callback into an `InteractionCommand` and runs it through
`CommandExecutor`. This is the only inbound entry point that resolves the actor's role, so every
`@bot ...` command (status, role grant/revoke, cve topic ops, agent turns) is gated here before the
domain context sees it.

## Key Files
| File | Description |
|------|-------------|
| `AppMentionEventHandler.kt` | Interface: `parseAppMentionEvent(headers, payload): InboundCommand`, `handleEvent(commandData): CommandOutput`, `handleEvent(headers, payload): CommandOutput`. Taken by `SlackEventController` and `SocketModeReceiver` |
| `SlackMentionEventHandlerImpl.kt` | `@Service`. Constants `SLACK_APPID_KEY_NAME = "api_app_id"` and `SLACK_APP_NAME = "CodeCompanion"` (the latter is reused by `SlackInteractionHandlerImpl`). Parse: `resolveAppId` (throws `AppIdNotFoundException` / `APP_ID_NOT_FOUND`), `jsonMapper.convertValue(payload, SlackEventCallBackRequest)`, `resolveCommandType` validates `SlackEventType.valueOf(type.uppercase())` (throws `UnsupportedSlackCommandTypeException` / `UNSUPPORTED_SLACK_COMMAND_TYPE`), then `toMentionInboundCommand(appId, channelName, actorName)`. Both `handleEvent` overloads are `@Transactional`; `buildCommand` sets `actorRole = commandRoleResolver.resolve(userId = commandData.actorId)` |

## For AI Agents

### Working In This Directory
- **Role is resolved per call** through `CommandRoleResolver.resolve(userId = commandData.actorId)`
  (bootstrap-admins config → `user_command_role` row → `USER`). Never cache it on the bean; a
  mid-conversation revoke must apply to the next mention.
- **One transaction.** `handleEvent(headers, payload)` calls `handleEvent(commandData)` on `this`, so the
  inner `@Transactional` is not re-proxied — the outer call is the boundary. `BEFORE_COMMIT` listeners
  (`MeetingServiceImpl.createNewMeeting`, `SlackMessageRelayServiceImpl.saveOutboxMessage`) attach to it,
  and `CommandExecutor` re-throws so a publish failure rolls the whole mention back.
- **Idempotency** comes from `IdempotencyCreator.create(data = commandData)`; a Slack retry of the same
  event yields the same key, which is what the outbox and the domain contexts dedupe on.
- `channel_name` / `user_name` do not exist on an `app_mention` callback (they are slash-command form
  fields), so the handler reads them as optional strings and passes `""` when absent — consumers such as
  `AgentConverseService.contextPrompt` fall back to `<@id>` / `<#id>` mentions on blank. Never use
  `.toString()` on a nullable payload lookup here; that once produced the literal `"null"`.
- `resolveCommandType` only rejects unknown transport types; the resulting `SlackEventType` is discarded
  and `toMentionInboundCommand` does the actual mapping. The `FIXME Remove AppMention Events` note means
  this handler is slated to shrink — do not grow it with new parsing.
- The non-`app_mention` filter lives upstream: `SlackEventController` ACKs other event types with an empty
  200, `SocketModeReceiver.handleEvent` forwards only `event.type == "app_mention"`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.mention.SlackMentionEventHandlerImplTest'
```
Spec under `application/src/test/kotlin/dev/notypie/application/service/mention/`. Fixture:
`createAppMentionPayload` (application testFixtures, same package). MockK `CommandExecutor` and
`CommandRoleResolver`; assert the `InteractionCommand` passed to `execute` (app name, role, idempotency
key) and the thrown `AppIdNotFoundException` / `UnsupportedSlackCommandTypeException` for bad payloads.

### Common Patterns
- Interface + `Impl` pair; controllers depend on the interface.
- `exceptionDetails { key value v because "..." }` DSL from `domain/common/error` for typed parse errors.
- `runCatching { enumValueOf }.getOrElse { throw Typed(...) }` for transport enum validation.
- `jsonMapper` (`infrastructure/common`) for `Map → DTO` conversion, never a fresh `ObjectMapper`.

## Dependencies

### Internal
- `application/service/command/` — `CommandExecutor`, `CommandRoleResolver`
- `application/common/IdempotencyCreator`
- `application/exception/` — `AppIdNotFoundException`, `UnsupportedSlackCommandTypeException`,
  `PayloadParseErrorCode`
- `infrastructure/impl/command/slack/` — `SlackEventCallBackRequest`, `SlackEventType`,
  `SlackMentionMapper.toMentionInboundCommand`
- `infrastructure/common/jsonMapper`
- `domain/command/entity/InteractionCommand`, `domain/command/inbound/InboundCommand`,
  `domain/command/dto/response/CommandOutput`, `domain/common/error/exceptionDetails`

### External
Spring `@Service` / `@Transactional`, `MultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
