<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/mention

## Purpose
Spec for `SlackMentionEventHandlerImpl.parseAppMentionEvent`, the step that turns a raw `app_mention`
event map into an `InboundCommand`. Only parsing is covered; the dispatch path through `CommandExecutor`
is not exercised here.

## Key Files
| File | Description |
|------|-------------|
| `SlackMentionEventHandlerImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Payload with `api_app_id` → `appId == TEST_APP_ID`, `channel == TEST_CHANNEL_ID`, `actorId == TEST_USER_ID`, `appToken == TEST_BOT_TOKEN`; `appId = null` → `AppIdNotFoundException`; `type = "not_a_real_type"` → `UnsupportedSlackCommandTypeException` with `rawCommandType` echoed; `botId = null` (human-typed mention, regression) → parses; `botId = "B001"` (app-posted, with `bot_profile`) → parses; custom `channel`/`publisherId`/`userName` → reflected in `channel`/`actorId`. |

## For AI Agents

### Working In This Directory
- `createAppMentionPayload` builds the Slack event as `Map<String, Any>`; `botId` toggles the presence of
  `app_id`, `bot_id`, `channel_type`, and `bot_profile` inside `event`. The human-mention case exists because
  parsing once required those keys.
- `event.ts` is a string and `event_ts` a double in the fixture, mirroring the wire shape; a parser change
  that types either differently will surface here first.
- `commandExecutor` is `relaxed` and `commandRoleResolver` strict, but neither is called by
  `parseAppMentionEvent`; they exist only to construct the handler.
- Headers are a `LinkedMultiValueMap` with `Content-Type: application/json`; nothing asserts on them.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.mention.*'
```
Fixtures used: `application` testFixtures `service/mention/AppMentionPayloadCreator.kt`
(`createAppMentionPayload`), `domain` testFixtures `Constants.kt` (`TEST_APP_ID`, `TEST_BOT_TOKEN`,
`TEST_CHANNEL_ID`, `TEST_USER_ID`).

### Common Patterns
- Exceptions are asserted with `shouldThrow<...>` and, for the unsupported type, by reading the exception's
  `rawCommandType` field rather than its message.

## Dependencies

### Internal
- `application/service/mention/SlackMentionEventHandlerImpl.kt`, `application/service/command/CommandExecutor`,
  `CommandRoleResolver`, `application/exception/AppIdNotFoundException`,
  `UnsupportedSlackCommandTypeException`

### External
MockK, Kotest, Spring `HttpHeaders`/`LinkedMultiValueMap`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
