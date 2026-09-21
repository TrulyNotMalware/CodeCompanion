<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/impl/command/slack

## Purpose
Slack wire DTOs and the small enums/mappers that sit directly on them: the parsed `InteractionPayload`
and its `States`, the Events API envelope (`SlackEventCallBackRequest` / `EventCallbackData`), the slash
command form body, Block Kit fragments (`Block`, `Element`), and the app-mention → `InboundCommand` mapper.
Nothing here is a Spring bean.

## Key Files
| File | Description |
|------|-------------|
| `InteractionPayload.kt` | `InteractionPayload(type: CommandDetailType, team, user, triggerId, isEnterprise, enterprise?, idempotencyKey, apiAppId, botId, token, container, channel, responseUrl, states, currentAction, routingExtras = [], privateMetadata?)`; extensions `isCompleted()`, `isPrimary()`, `isCanceled()` |
| `States.kt` | `States(isSelected = false, type: ActionElementTypes, selectedValue = "", blockId?)` — one per form element; `blockId` is set by the view_submission parser only |
| `ActionElementTypes.kt` | `enum (elementName, isPrimary)`: `APPLY_BUTTON`, `REJECT_BUTTON`, `BUTTON` are primary (`"button"`); selects, pickers, `CHECKBOX`, `RADIO_BUTTONS`, `PLAIN_TEXT_INPUT`, `UNKNOWN` are not |
| `InteractionTypes.kt` | `VIEW_SUBMISSION = "view_submission"`, `BLOCK_ACTIONS = "block_actions"` |
| `Container.kt` | `Container(type, messageTime: Instant, isEphemeral, …, viewId?, messageTs?)`, `Channel(id, name)`, `Enterprise(id, name)` |
| `Users.kt` | `User(id, userName, name, teamId)`, `Team(id, domain)` |
| `SlackEventCallBackRequest.kt` | Events API envelope: `token`, `team_id`, `api_app_id`, `event: EventCallbackData`, `type`, `event_id`, `event_time`, `authorizations: List<Authorization>`, `is_ext_shared_channel`, `event_context` |
| `EventCallbackData.kt` | `app_mention` body: `client_msg_id?`, `type`, `text`, `user`, `app_id?` / `bot_id?` / `bot_profile?`, `ts: String`, `thread_ts?`, `blocks: List<Block>`, `team`, `channel`, `event_ts: Double`, `channel_type?`; plus `BotProfile`, `Icons` |
| `Authorization.kt` | `(enterprise_id?, team_id, user_id, is_bot, is_enterprise_install)` — `is_bot` identifies the bot's own user id |
| `Block.kt` / `Element.kt` | `Block(type, block_id, elements, fields, text: TextElement?)`; `Element(type, text, user_id, image_url, alt_text, verbatim, action_id, value, style, elements)`, `Element.extractText()`, `sealed TextElement` (`TextObject` / `PlainText`) with `TextValueDeserializer` accepting a string or an object |
| `SlackEventType.kt` | `URL_VERIFICATION`, `EVENT_CALLBACK`, `APP_MENTION` |
| `SlackMentionMapper.kt` | `SlackEventCallBackRequest.toMentionInboundCommand(appId, channelName, actorName)`: walks the `rich_text` → `rich_text_section`, drops the bot's own mention, splits text on single spaces with blanks removed, sets `hasCommandStructure` |
| `SlashCommandRequestBody.kt` | Slash command form (`text` → `subCommands`, `channel_id`, `user_id`, `trigger_id`, `response_url`, …); `toInboundCommand()` (`InboundKind.SLASH`, `SlashInvocation(TriggerHandle)`), `subCommandList()` |

## For AI Agents

### Working In This Directory
- **`ts` stays a `String`.** It is a message id (thread anchor, `chat.update` target) and a `Double`
  round-trip mangles the fixed-point form. `event_ts` is already a `Double` and is only used by fixtures.
- **`InteractionPayload.type` comes from `CommandDetailType.valueOf`** in the parser; renaming a
  `CommandDetailType` constant breaks routing for messages already posted with the old name in their text.
- **`isCompleted()` has no callers**; `isPrimary()` / `isCanceled()` gate `SlackInteractionHandlerImpl`.
  `Enterprise` is declared but never constructed.
- **`SlashCommandRequestBody.subCommandList()` splits on a single space without dropping blanks**, unlike
  the mention mapper — a double space or a trailing space yields empty tokens for the slash services.
- **Jackson annotations here are `com.fasterxml.jackson.annotation`** (retained by Jackson 3) on
  `@field:`; the custom deserializer is `tools.jackson.databind.ValueDeserializer`. `verbatim` and
  `emoji` default to `true` when absent.
- `Element` carries a `FIXME` to become a sealed hierarchy; today every Block Kit element shares one class
  with nullable fields, so check `type` before reading `userId` / `value`.
- Consumers in `:application`: `SlackRequestParser`, `SlackEventController`, `SlackMentionEventHandlerImpl`,
  `SlackInteractionHandlerImpl`, and the meeting / standup / CVE slash services.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.slack.*'
```
`ElementTest` (string vs object `text` deserialization) and `SlackMentionMapperTest` (bot filtering,
tokenizing, `hasCommandStructure`). Fixtures: `testFixtures/.../impl/command/slack/InteractionPayloadCreator`,
`SlackEventCallBackRequestCreator`, `.../impl/command/BlockActionPayloadCreator`.

### Common Patterns
- `data class` DTOs with `@field:JsonProperty("snake_case")`; optional wire fields are nullable with `null`
  defaults, required ones non-null.
- Boundary mappers are extension functions on the DTO (`toMentionInboundCommand`, `toInboundCommand`).

## Dependencies

### Internal
- `domain/command/entity/CommandDetailType`, `domain/command/inbound/*` (`InboundCommand`, `InboundKind`,
  `MentionInvocation`, `SlashInvocation`, `MessageHandle`, `TriggerHandle`)

### External
Jackson annotations, Jackson 3 databind (`ValueDeserializer`, `JsonNode`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
