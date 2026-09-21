<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/templates

## Purpose
Specs for the Slack Block Kit builders in main `templates/`: element-level, block-level and full-template /
modal-view JSON. Plain Kotest `BehaviorSpec`s; the only collaborator is a MockK `RestRequester` for the
`users.profile.get` lookup. Modal JSON is validated by round-tripping it through the Slack SDK's `View`
deserializer.

## Key Files
| File | Description |
|------|-------------|
| `ModalElementBuilderTest.kt` | `ModalElementBuilder`: `textObject` (plain vs markdown), `plainTextObject` (`emoji = true`), `markdownTextObject` (`verbatim = true`), `imageBlockElement`, `approvalButtonElement` (`APPLY_BUTTON`, `primary`, value = payload) and `rejectButtonElement` (`REJECT_BUTTON`, `danger`), `confirmationDialogObject`, `selectionElement` (`MULTI_STATIC_SELECT` with option text / value), `multiUserSelectionElement`, `plainTextInputElement` placeholder, `timePickerElement` / `datePickerElement` default placeholders, `checkboxElements`, `radioButtonElements`, `optionElement` (markdown vs plain text) |
| `ModalBlockBuilderTest.kt` | `ModalBlockBuilder`: `headerBlock`, `dividerBlock`, `simpleText`, `textBlock` (fields), `timeScheduleBlock` (accessory image, `info.toString()` text), `approvalBlock` (`ActionsBlock` with `APPLY_BUTTON` + `REJECT_BUTTON` states), `selectionBlock`, `multiUserSelectBlock` (`InputBlock`), `plainTextInputBlock`, `calendarThumbnailBlock` (`"*Schedule*\nDetails here"`), `userNameWithThumbnailBlock` (`ContextBlock`, 3 elements), `selectDateTimeScheduleBlock` (`DATE_PICKER`, `TIME_PICKER`, `TIME_PICKER` in that order), `checkBoxesBlock`, `radioButtonBlock` |
| `ModalTemplateBuilderTest.kt` | `ModalTemplateBuilder(modalBlockBuilder, restRequester, slackApiToken)`. Message templates: `requestApprovalFormTemplate` (4 states, 5 blocks), `requestMeetingFormTemplate` (checkbox / users / date / time / apply / reject, 10 blocks), `timeScheduleNoticeTemplate` (3 states), `simpleScheduleNoticeTemplate`, `approvalTemplate` (stubs `restRequester.get("users.profile.get?user=...")` with a `SlackUserProfileDto`), `onlyTextTemplate`, `simpleTextResponseTemplate`, `errorNoticeTemplate` (3 / 4 blocks). `meetingListFormTemplate`: empty state, single / multiple meetings with dividers only between rows, participant count `accepted/total` including the host, declined list with `RejectReason.showMessage` and the free-text `OTHER` detail, `[CANCELED]` marker, host-only actions block (`reschedule` / `add-participant` / `cancel` buttons whose `block_id` / `action_id` are suffixed with the meeting uid and whose values are `"<listKey>,<TYPE>,<meetingUid>"`), id uniqueness across multiple host rows, no actions on canceled meetings, `MAX_MEETINGS_PER_LIST` truncation notice and the 50-block ceiling in both viewer modes, missing `endAt`. Modal views: `declineReasonModalViewJson` (5-token `private_metadata`, blank title omits the section, blank channel / ts keep the token positions, 8 `RejectReason` options without `ATTENDING`, optional detail input), `rescheduleMeetingModalViewJson` (pre-filled `initial_date` / `initial_time`), `standupModalViewJson` (one multiline input per question), `standupSetupModalViewJson` (8 input blocks and every element type), `cveSubscribeModalViewJson` / `cveUnsubscribeModalViewJson` (options = topic keys) — each parsed back with `GsonFactory.createSnakeCase().fromJson(json, View::class.java)` |

## For AI Agents

### Working In This Directory
- **Parse every emitted modal through the Slack SDK `View` model.** That round-trip is this repo's Block Kit
  validator: a hand-built JSON string that Gson cannot map into `View` / `InputBlock` /
  `StaticSelectElement` would be rejected by `views.open` in production. New modal builders get the same
  `then`.
- **`private_metadata` token order is a contract** read positionally by the parser and by
  `ViewSubmissionChannelRoutingRegressionTest` in `impl/command/`. Assert the full string here and add a
  flow there when you add a token.
- The 50-block ceiling cases (`MAX_MEETINGS_PER_LIST + 5` as viewer, `+ 3` as host) exist because Slack
  rejects messages over 50 blocks; the host mode is the worst case (`3N + 2` blocks). Keep both when
  changing the list layout.
- Block / state counts (`template.size shouldBe 5`) are intentional golden numbers; when a template changes,
  update the count in the same commit and say why in the `then` name.
- `approvalTemplate` is the only path that calls `RestRequester`; stub it with the exact `uri` /
  `authorizationHeader` / `responseType` or the strict mock throws.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.templates.*'
```
No Spring, no network.

### Common Patterns
- `shouldBeInstanceOf<HeaderBlock>()` / `SectionBlock` / `ActionsBlock` on `result.template[i]`;
  `interactionStates.map { it.type }` against `ActionElementTypes`.
- Meeting inputs from `:domain` fixtures (`createMeetingDto`, `createMeetingParticipantDto`,
  `createApprovalContents`); id constants `MeetingActionIds`, `DeclineReasonModalIds`,
  `RescheduleMeetingModalIds`, `StandupModalIds`, `StandupSetupModalIds`, `CveSubscriptionModalIds` from main.
- `json shouldContain "\"key\":\"value\""` for envelope fields, `View` parsing for structure.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/templates/` (+ `dto/`) — builders, `*ModalIds`,
  `TimeScheduleAlertContents`, `CheckBoxOptions`
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/` — `RestRequester`, `dto/SlackUserProfileDto`
  (+ `Profile`), `slack/ActionElementTypes`
- `domain/command/dto/modals`, `domain/command/outbound/TopicOption`, `domain/meet/entity/RejectReason`
- `domain/src/testFixtures/kotlin/dev/notypie/domain/`

### External
Kotest, MockK, Slack SDK model (`com.slack.api.model.*`) + `GsonFactory`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
