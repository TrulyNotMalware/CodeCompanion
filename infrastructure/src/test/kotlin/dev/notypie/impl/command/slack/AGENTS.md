<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/command/slack

## Purpose
Specs for the Slack Events API wire model in main `impl/command/slack/`: the polymorphic `text` field of a
rich-text `Element` and the `app_mention` flattening into the neutral `MentionInvocation`. Plain Kotest, no
Spring.

## Key Files
| File | Description |
|------|-------------|
| `ElementTest.kt` | `Element.extractText()` returns the `PlainText.value`, the `TextObject.text`, or `null`; `TextValueDeserializer` (through a local Jackson 3 `JsonMapper` + `KotlinModule`) reads `"text":"hello"` as `PlainText`, an object as `TextObject` (`verbatim` / `emoji` default `true`), and `null` as `null`. Also pins the `PlainText` / `TextObject` constructors A rich_text leaf with an object `style` (`{"bold":true}`) deserializes and keeps its text; a list's string style is kept. |
| `SlackMentionMapperTest.kt` | `SlackEventCallBackRequest.toMentionInboundCommand(appId, channelName, actorName)` → `InboundCommand` with `InboundKind.MENTION` and a `MentionInvocation` payload. Golden cases ported from the old domain `AppMentionContextParserTest`: the bot's own id (from `authorizations`) is dropped from `mentionedUserIds`; command text splits on whitespace with blanks removed; empty `blocks` → `hasCommandStructure = false`; a mention-only section → `true` with zero tokens; `ts` becomes `message`, `thread_ts` becomes `thread` (null at top level) A workflow `app_mention` with `bot_id` but no `user` or `blocks` (`createWorkflowAppMentionJson`) parses with the shared `jsonMapper` and maps to a blank `actorId` with `hasCommandStructure = false`. A realistic app_mention (`createRichAppMentionJson`: bold / code styles, labelled and bare links, a user, a channel, an emoji, a code block, a quote, an ordered list with a broadcast) restores `text` in order with the bot mention dropped, while `commandTokens` / `mentionedUserIds` read as before. |

## For AI Agents

### Working In This Directory
- **The two "no command" shapes are distinct on purpose**: no rich-text structure (domain replies "not
  supported") versus structure with zero tokens (domain throws). Keep both cases when touching the mapper.
- The bot-id filter reads the `authorizations` entry with `isBot = true`; build requests with
  `createAuthorization(userId = botId, isBot = true)` or the filter silently keeps the bot mention.
- `ElementTest` builds its own mapper and deliberately does not use `dev.notypie.common.jsonMapper`, so a
  change to the shared mapper's settings cannot mask a deserializer bug here.
- Routing on the flattened tokens is tested in `:domain`, not here — this package only pins the boundary.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.slack.*'
```
No Spring, no network.

### Common Patterns
- Request graphs from `testFixtures/.../impl/command/slack/SlackEventCallBackRequestCreator.kt`
  (`createSlackEventCallBackRequest`, `createEventCallbackData`, `createRichTextBlock`, `createUserElement`,
  `createTextElement`, `createAuthorization`).
- `shouldBeInstanceOf<T>()` to narrow sealed / polymorphic results before asserting fields.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/slack/` — `Element`, `PlainText`, `TextObject`,
  `SlackMentionMapper`, `SlackEventCallBackRequest`
- `infrastructure/src/testFixtures/kotlin/dev/notypie/impl/command/slack/`
- `domain/command/inbound` — `MentionInvocation`, `InboundKind`

### External
Kotest, Jackson 3 + Kotlin module.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
