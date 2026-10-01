<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

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
| `ModalBlockBuilderTest.kt` | `ModalBlockBuilder`: `headerBlock`, `dividerBlock`, `simpleText` (a 4,000-character text is cut to the 3,000 cap with the marker), `textSections` (one section when it fits, several plain-text sections within the budget otherwise), `textBlock` (fields), `timeScheduleBlock` (accessory image, `info.toString()` text), `approvalBlock` (`ActionsBlock` with `APPLY_BUTTON` + `REJECT_BUTTON` states), `selectionBlock`, `multiUserSelectBlock` (`InputBlock`), `plainTextInputBlock`, `calendarThumbnailBlock` (`"*Schedule*\nDetails here"`), `userNameWithThumbnailBlock` (`ContextBlock`, 3 elements), `selectDateTimeScheduleBlock` (`DATE_PICKER`, `TIME_PICKER`, `TIME_PICKER` in that order), `checkBoxesBlock`, `radioButtonBlock` |
| `SlackBlockLimitsTest.kt` | `splitSectionText`: text that fits is returned as one unchanged chunk; a 400-line answer splits on line boundaries within the 2,900 budget and rejoins to the original; one over-long line wraps at spaces with nothing lost; more chunks than `maxSections` keeps that many and ends with `TRUNCATION_MARKER`; a code block cut by a boundary is closed and re-opened so every chunk has balanced fences (also when the truncation lands inside the block); a hard wrap never splits a surrogate pair or an `&amp;` entity. `truncateSectionText` (≤ 3,000 with the marker) and `truncatePlainText` (ellipsis at the limit) |
| `SlackMrkdwnTest.kt` | `escapeMrkdwn`: `<!channel>` and `<url\|label>` become literal, a version range `< 2.3.1` survives, `&` is escaped first so an existing entity is not decoded back into markup, plain emphasis passes through unchanged. `neutralizeBroadcastMentions`: `<!channel>`, `<!here>`, `<!everyone>`, `<!group>`, a labelled `<!here\|here>` and `<!subteam^…\|@team>` become literal; links, `<@U…>`, emphasis and `<!date^…>` survive |
| `ModalTemplateBuilderTest.kt` | `ModalTemplateBuilder(modalBlockBuilder, restRequester, slackApiToken)`. Message templates: `requestApprovalFormTemplate` (4 states, 5 blocks), `requestMeetingFormTemplate` (checkbox / users / date / time / apply / reject, 10 blocks), `timeScheduleNoticeTemplate` (3 states), `simpleScheduleNoticeTemplate`, `approvalTemplate` (stubs `restRequester.safeGet(uri = "users.profile.get?user={user}", uriVariables = mapOf("user" to TEST_USER_ID), ...)` with a `SlackUserProfileDto`; a failing lookup still renders with the `<@id>` fallback), `onlyTextTemplate`, `simpleTextResponseTemplate`, `errorNoticeTemplate` (3 / 4 blocks); a long AI answer through `simpleTextResponseTemplate` becomes several sections within the budget that rejoin to the answer, a body needing more than 50 blocks stops at exactly 50 with the truncation marker, and a long `onlyTextTemplate` message is sections only (T3). User text with `<!channel>`, a disguised link and `&` is escaped in the standup summary (routine name, question, answer — the member mention stays markup), the meeting list (title, decline detail — the decliner mention stays), the decline modal title, and an approval's profile display name and subtitle (T21). `meetingListFormTemplate`: empty state, single / multiple meetings with dividers only between rows, participant count `accepted/total` including the host, declined list with `RejectReason.showMessage` and the free-text `OTHER` detail, `[CANCELED]` marker, host-only actions block (`reschedule` / `add-participant` / `cancel` buttons whose `block_id` / `action_id` are suffixed with the meeting uid and whose values are `"<listKey>,<TYPE>,<meetingUid>"`), id uniqueness across multiple host rows, no actions on canceled meetings, `MAX_MEETINGS_PER_LIST` truncation notice and the 50-block ceiling in both viewer modes, missing `endAt`. Modal views: `declineReasonModalViewJson` (5-token `private_metadata`, blank title omits the section, blank channel / ts keep the token positions, 8 `RejectReason` options without `ATTENDING`, optional detail input capped at `max_length` 255 — T22), `rescheduleMeetingModalViewJson` (pre-filled `initial_date` / `initial_time`), `standupModalViewJson` (one multiline input per question, all sharing a `max_length` that keeps question lines + answers within the section budget; eight 199-character `&` questions get the floor `STANDUP_ANSWER_MIN_LENGTH` instead of 1, and a member answering at it is cut to the budget with the marker — H10), `standupSummaryTemplate` (T4: five members × three 700-character answers render as title + five uncut sections; 30 members answering eight 199-character questions at the modal's cap stay at 31 blocks, none cut; a stored 5,000-character answer is cut to the budget with the marker; a non-responder reads `<@id> _(no response)_`), `standupSetupModalViewJson` (8 input blocks and every element type), `cveSubscribeModalViewJson` / `cveUnsubscribeModalViewJson` (options = topic keys; a 120-character display name becomes a 75-character option text ending in `…` with the full key as value, and 101 topics render only the first 100 options — V6) — each parsed back with `GsonFactory.createSnakeCase().fromJson(json, View::class.java)` |
| `SlackUserProfileResolverTest.kt` | `SlackUserProfileResolver(restRequester, slackApiToken, clock, ttl, failureTtl, maxEntries)` with a MockK `RestRequester` and a local mutable `Clock`. Cases: a hit is cached (one lookup for two resolves) and the user id is passed as the `{user}` URI variable; blank display name falls back to the real name without an empty thumbnail; a failed lookup degrades to `<@id>` and is negative-cached for the 60 s failure TTL; an `ok = false` response behaves the same; an entry older than `ttl` is fetched again; at `maxEntries` an expired entry is evicted before a live one, and with none expired only the oldest entry goes; three threads that miss while the first lookup is blocked inside the mock (released only once they are all parked) share it — one lookup, four identical views; a lookup that throws propagates and frees the in-flight slot, so the next resolve asks again |

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
- `approvalTemplate` is the only path that calls `RestRequester` (through `SlackUserProfileResolver`); stub
  `safeGet` with the exact `uri` / `authorizationHeader` / `responseType` / `uriVariables` (or `any()`) or the
  strict mock throws.

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
