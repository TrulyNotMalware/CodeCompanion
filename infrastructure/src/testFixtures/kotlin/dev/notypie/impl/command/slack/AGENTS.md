<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl/command/slack

## Purpose
Typed creators for the parsed Slack models in main `impl/command/slack/`: an already-parsed
`InteractionPayload` (bypassing the JSON parser) and an Events API `SlackEventCallBackRequest` graph for
`app_mention` handling.

## Key Files
| File | Description |
|------|-------------|
| `InteractionPayloadCreator.kt` | `createTestContainer()` → `Container(type = BLOCK_ACTIONS, isEphemeral = false, messageTime = now)`; `createInteractionPayloadInput(commandDetailType, currentAction: States, states: List<States>, user, team, channel, container, enterprise = null, apiAppId, botId, token, responseUrl, idempotencyKey: UUID, triggerId = "", isEnterprise = false): InteractionPayload`; `selectedApplyButtonStates()` (`APPLY_BUTTON`, selected, value `"apply"`), `selectedRejectButtonStates()` (`REJECT_BUTTON`, selected). Private `TEST_USER` / `TEST_TEAM` / `TEST_CHANNEL` defaults built from `:domain` constants |
| `SlackEventCallBackRequestCreator.kt` | `createSlackEventCallBackRequest(token, teamId, apiAppId, type = "event_callback", eventId = "Ev0001", eventTime, eventContext, isExtSharedChannel, event = createEventCallbackData(), authorizations = [createAuthorization()])`; `createAuthorization(enterpriseId = null, teamId, userId, isBot = true, isEnterpriseInstall = false)`; `createEventCallbackData(type = "app_mention", userId, appId = null, botId = null, channel, teamId, blocks = [], ts = TEST_MESSAGE_TS, threadTs = null)` — builds a `BotProfile` only when `botId` is given; `createRichTextBlock(vararg elements)` wraps elements in a `rich_text_section`; `createUserElement(userId)`, `createTextElement(text)` |

## For AI Agents

### Working In This Directory
- **`createInteractionPayloadInput` requires `idempotencyKey` and `commandDetailType` explicitly** (no
  defaults) so a spec cannot accidentally test routing with a stale key; keep them required.
- `container.messageTs` is *not* set by `createTestContainer()`; specs that need a message handle use
  `.copy(container = base.container.copy(messageTs = ...))`, and `routingExtras` via `.copy(routingExtras = ...)`.
- `createEventCallbackData` defaults to a human-typed mention (`appId = null`, `botId = null`); pass `botId`
  to simulate an app-posted mention with a `BotProfile`. The mention mapper filters the bot by the
  `authorizations` entry, so supply `createAuthorization(userId = botId, isBot = true)` on the request.
- `selectedApplyButtonStates` / `selectedRejectButtonStates` are consumed from `:application` specs; keep
  their `isSelected` / `selectedValue` shape stable.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.SlackInboundMapperTest'
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.slack.SlackMentionMapperTest'
```
Consumers that pin these shapes; the fixtures have no specs of their own.

### Common Patterns
- Top-level `create*` functions with defaults from `:domain` `Constants.kt` (`TEST_USER_ID`, `TEST_TEAM_ID`,
  `TEST_CHANNEL_ID`, `TEST_APP_ID`, `TEST_BOT_ID`, `TEST_TOKEN`, `TEST_BASE_URL`, `TEST_MESSAGE_TS`).
- Private extension `ActionElementTypes.toStates(isSelected, selectedValue)` for one-line `States`.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/slack/` — `InteractionPayload`, `States`,
  `Container`, `User`, `Team`, `Channel`, `Enterprise`, `SlackEventCallBackRequest`, `EventCallbackData`,
  `Authorization`, `BotProfile`, `Icons`, `Block`, `Element`, `PlainText`
- `domain/command/entity/CommandDetailType`; `domain/src/testFixtures/.../Constants.kt`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
