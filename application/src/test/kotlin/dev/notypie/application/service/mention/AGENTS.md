<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/service/mention

## Purpose
Spec for `SlackMentionEventHandlerImpl.parseAppMentionEvent`, the step that turns a raw `app_mention`
event map into an `InboundCommand`, and for the app-posted filter in `handleEvent(headers, payload)`. The
transactional dispatch path (role resolved before the
mention transaction, then `CommandExecutor` inside it) is exercised on a real `JpaTransactionManager` by
`service/command/RoleLookupJpaTransactionTest`.

## Key Files
| File | Description |
|------|-------------|
| `SlackMentionEventHandlerImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Payload with `api_app_id` → `appId == TEST_APP_ID`, `channel == TEST_CHANNEL_ID`, `actorId == TEST_USER_ID`, `appToken == TEST_BOT_TOKEN`; `appId = null` → `AppIdNotFoundException`; `type = "not_a_real_type"` → `UnsupportedSlackCommandTypeException` with `rawCommandType` echoed; `botId = null` (human-typed mention, regression) → parses; `botId = "B001"` (app-posted, with `bot_profile`) → parses; custom `channel`/`publisherId`/`userName` → reflected in `channel`/`actorId`/`actorName`; default payload without display names (as a real `app_mention` callback arrives) → `actorName` and `channelName` are `""`, not the string `"null"` (regression); a workflow-shaped payload without `user` and `blocks` parses with a blank `actorId` (A9). `handleEvent(headers, payload)`: this app's own message (`bot_id` with `botAppId = TEST_APP_ID`), one where only `bot_profile.app_id` names this app, or no `user` → `Status.DO_NOTHING` with no role lookup and no `execute`; a person's mention, and a person posting through another app (`bot_id` + `user`, `botAppId = "A_OTHER_APP"`, H5 — it used to be dropped) → role resolved once and `execute` once. `withoutEventKeys(...)` (file-private) strips keys from the fixture's `event` map. |

## For AI Agents

### Working In This Directory
- `createAppMentionPayload` builds the Slack event as `Map<String, Any>`; `botId` toggles the presence of
  `app_id`, `bot_id`, `channel_type`, and `bot_profile` inside `event`, and `botAppId` (default: this app) names
  the posting app in both `app_id` fields — the handler drops only this app's own messages. The human-mention case exists because
  parsing once required those keys.
- `event.ts` is a string and `event_ts` a double in the fixture, mirroring the wire shape; a parser change
  that types either differently will surface here first.
- `commandExecutor` is `relaxed` and `commandRoleResolver` strict, but neither is called by
  `parseAppMentionEvent`; they and `createH2TransactionManager()` exist only to construct the handler. The
  `handleEvent` cases build their own handler with strict mocks so an unexpected call fails.
- `CommandExecutor.execute` is generic: verify it as `execute<SubCommandDefinition>(command = any())`.
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
