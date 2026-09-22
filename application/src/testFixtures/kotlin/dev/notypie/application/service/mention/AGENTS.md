<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# application/src/testFixtures/kotlin/dev/notypie/application/service/mention

## Purpose
Builds the untyped `Map<String, Any>` a Slack Events API `app_mention` callback arrives as, for
`SlackMentionEventHandlerImpl.handleEvent(headers, payload)` — the one place in the application layer that
still sees raw Slack JSON before infrastructure maps it.

## Key Files
| File | Description |
|------|-------------|
| `AppMentionPayloadCreator.kt` | `createAppMentionPayload(appId = TEST_APP_ID, token = TEST_BOT_TOKEN, teamId = TEST_TEAM_ID, type = "event_callback", eventId = "Ev0001", eventTime, eventContext, isExtSharedChannel = false, userName = null, channelName = null, publisherId = TEST_USER_ID, channel = TEST_CHANNEL_ID, eventType = "app_mention", botId = null): Map<String, Any>` |

## For AI Agents

### Working In This Directory
- Sole consumer: `SlackMentionEventHandlerImplTest`, which drives the envelope guards — `appId = null`
  drops `api_app_id`, `type = "not_a_real_type"` fails the callback-type check, `botId = "B001"` turns the
  event into an app-posted message that must be ignored.
- `botId` non-null adds `app_id`, `bot_id`, `channel_type` and a full `bot_profile` block to the inner
  `event`; a human mention (the default) carries none of them. Keep that switch when adding fields.
- Wire fidelity matters: `event.ts` is a **string**, `event_ts` is a **double**, `authorizations` is a
  one-element list. The mapper relies on these types. `user_name` / `channel_name` are **absent by
  default** because a real `app_mention` callback never carries them (they are slash-command form
  fields); pass them only to exercise the optional read in the handler.
- The typed counterpart is infrastructure's `impl/command/slack/SlackEventCallBackRequestCreator.kt`
  (`createSlackEventCallBackRequest`, `createEventCallbackData`, ...); use that for mapper specs and this
  map only for the handler entry point.
- Identity defaults come from domain `Constants.kt`; `TEST_BOT_TOKEN` is a placeholder, never a real token.

### Testing Requirements
No spec for the fixture itself; `SlackMentionEventHandlerImplTest` is the coverage.

### Common Patterns
- `buildMap { }` with `?.let { put(...) }` for optional keys so absence, not `null`, is what the handler
  sees.

## Dependencies

### Internal
None beyond the handler under test.

### External
- `dev.notypie.domain.TEST_*` constants from `testFixtures(project(":domain"))`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
