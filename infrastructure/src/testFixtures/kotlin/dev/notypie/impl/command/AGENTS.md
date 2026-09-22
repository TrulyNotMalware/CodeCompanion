<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl/command

## Purpose
Raw Slack interaction JSON builders for the parser specs. Every string here is shaped like what Slack posts
to the interactions endpoint (`block_actions` and `view_submission`), assembled from the same
`ActionElementTypes` / `InteractionTypes` / `*ModalIds` constants main uses, so a renamed constant breaks the
fixture instead of silently drifting.

## Key Files
| File | Description |
|------|-------------|
| `BlockActionPayloadCreator.kt` | **Action arrays**: `buttonActionJson(buttonType = PRIMARY, value, actionId)`, `buttonActionJsonWithoutStyle(value, actionId)`, `multiStaticSelectActionJson(selectedValues, actionId)`, `multiUsersSelectActionJson(selectedUsers, actionId)`, `unknownActionJson(type, actionId)`. **State values**: `stateValuesJson(blockId, actionId, stateEntry)` and the multi-block `stateValuesJson(vararg blockId to stateEntry)` (action id = `"<blockId>_action"`), `multiStaticSelectStateJson(selectedOptions: List<Pair<text, value>>)`, `multiUsersSelectStateJson(selectedUsers)`, `plainTextInputStateJson(value)`, `datepickerStateJson(selectedDate)`, `timepickerStateJson(selectedTime)`, `checkboxesStateJson(selectedOptions)`, `unknownStateJson(type)`. **View submissions**: `createDeclineReasonViewSubmissionJson(meetingIdempotencyKey, participantUserId, selectedReason, noticeChannel = "", noticeMessageTs = "", ...)` — 3-token `private_metadata` when channel / ts are blank, 5-token otherwise; `createStandupAnswerViewSubmissionJson(sessionUid, userId, responses, noticeChannel = "D_NOTICE", noticeMessageTs = "1700000000.000500", ...)` — one `standup_q_<i>` block per response; `createRoutingOnlyViewSubmissionJson(callbackId, privateMetadata, stateValues = "{}", ...)`. **Full payload**: `createBlockActionPayloadJson(idempotencyKey, commandDetailType = APPROVAL_REQUEST, isEphemeral = false, buttonType, buttonValue?, messageText?, stateValues = "{}", actions?, appId, token, responseUrl, teamId, teamDomain, userId, userName, channelId, channelName, botId)` — omits the `message` section when ephemeral, defaults `message.text` and the button value to `"<key>, <TYPE>"`, fixed `container.message_ts = "1234567890.123"` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `dto/` | `createProfile(displayName, realName, imageSize24)` — the 25-field `Profile` behind `SlackUserProfileDto` (see `dto/AGENTS.md`) |
| `slack/` | Typed `InteractionPayload` and Events API request creators (see `slack/AGENTS.md`) |
| `event/` | `SendSlackMessageEvent` / `OpenViewEvent` and payload-contents creators (see `event/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`createRoutingOnlyViewSubmissionJson` is the input to `ViewSubmissionChannelRoutingRegressionTest`;** its
  `privateMetadata` parameter must stay a pass-through so that spec can feed the writer's real string. Never
  give it a default that fabricates tokens.
- **The decline-reason builder keeps the legacy 3-token form** on purpose (blank channel / ts) so older parser
  cases still exercise the short metadata; do not collapse it to always emit 5 tokens.
- Use these builders instead of inline JSON in specs (the one two-time-picker block in
  `SlackInteractionRequestParserTest` is the accepted exception). Add a state / action builder here when a
  new Slack element type is parsed.
- Identity defaults (`TEST_APP_ID`, `TEST_TOKEN`, `TEST_TEAM_ID`, `TEST_USER_ID`, `TEST_CHANNEL_ID`,
  `TEST_BOT_ID`, `TEST_BASE_URL`, ...) come from `:domain` test fixtures — do not redefine them here.
- Consumed by `:infrastructure` specs and by `:application` through `testFixtures(project(":infrastructure"))`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.SlackInteractionRequestParserTest'
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.ViewSubmissionChannelRoutingRegressionTest'
```
Fixtures have no specs of their own; these two consumers pin their shape.

### Common Patterns
- Top-level functions returning raw JSON `String`s built with `"""..."""` templates and `joinToString` for
  arrays; defaults for every identity field; named arguments at call sites.
- Element type names via `ActionElementTypes.X.elementName`, interaction types via `InteractionTypes.*`,
  modal ids via `DeclineReasonModalIds` / `StandupModalIds`.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/slack/` — `ActionElementTypes`, `InteractionTypes`
- `infrastructure/src/main/kotlin/dev/notypie/templates/` — `ButtonType`, `DeclineReasonModalIds`,
  `StandupModalIds`
- `domain/command/entity/CommandDetailType`; `domain/src/testFixtures/.../Constants.kt`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
