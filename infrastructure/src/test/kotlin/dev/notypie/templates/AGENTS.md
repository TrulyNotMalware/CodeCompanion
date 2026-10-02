<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

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
| `ModalBlockBuilderTest.kt` | `ModalBlockBuilder`: `headerBlock`, `dividerBlock`, `simpleText`, `textBlock` (fields), `timeScheduleBlock` (accessory image, `info.toString()` text), `approvalBlock` (`ActionsBlock` with `APPLY_BUTTON` + `REJECT_BUTTON` states), `selectionBlock`, `multiUserSelectBlock` (`InputBlock`), `plainTextInputBlock`, `calendarThumbnailBlock` (`"*Schedule*\nDetails here"`), `userNameWithThumbnailBlock` (`ContextBlock`, 3 elements), `selectDateTimeScheduleBlock` (`DATE_PICKER`, `TIME_PICKER`, `TIME_PICKER` in that order), `checkBoxesBlock`, `radioButtonBlock`. `simpleText` over 3,000 characters is cut with the marker; `textSections`: short text is one section, long text splits within 2,900 and rejoins, a body over `MESSAGE_BODY_BUDGET` is cut and marked, seven code-block lines of 1,447 characters each get their own fenced section and stay within the message budget |
| `ModalTemplateBuilderTest.kt` | `ModalTemplateBuilder(modalBlockBuilder, restRequester, slackApiToken)`. Message templates: `requestApprovalFormTemplate` (4 states, 5 blocks), `requestMeetingFormTemplate` (checkbox / users / date / time / apply / reject, 10 blocks), `timeScheduleNoticeTemplate` (3 states), `simpleScheduleNoticeTemplate`, `approvalTemplate` (stubs `restRequester.safeGet(uri = "users.profile.get?user={user}", uriVariables = mapOf("user" to TEST_USER_ID), ...)` with a `SlackUserProfileDto`; a failing lookup still renders with the `<@id>` fallback), `onlyTextTemplate`, `simpleTextResponseTemplate`, `errorNoticeTemplate` (3 / 4 blocks; a 5,000-character message is cut to the 2,000-character field with `…`, long details become several sections within 2,900). Long bodies: `simpleTextResponseTemplate` splits a 200-line body into sections within 2,900 that rejoin to it, a blank headline renders a single section, every part of a 40,000-character answer split by `splitMessageText` renders untruncated with header plus sections within `MESSAGE_TEXT_BUDGET`; `onlyTextTemplate` renders only sections. `meetingListFormTemplate`: empty state, single / multiple meetings with dividers only between rows, participant count `accepted/total` including the host, declined list with `RejectReason.showMessage` and the free-text `OTHER` detail, `[CANCELED]` marker, host-only actions block (`reschedule` / `add-participant` / `cancel` buttons whose `block_id` / `action_id` are suffixed with the meeting uid and whose values are `"<listKey>,<TYPE>,<meetingUid>"`), id uniqueness across multiple host rows, no actions on canceled meetings, `MAX_MEETINGS_PER_LIST` truncation notice and the 50-block ceiling in both viewer modes, missing `endAt`. Modal views: `declineReasonModalViewJson` (5-token `private_metadata`, blank title omits the section, blank channel / ts keep the token positions, 8 `RejectReason` options without `ATTENDING`, optional detail input capped at `RejectReason.MAX_DETAIL_LENGTH`), `rescheduleMeetingModalViewJson` (pre-filled `initial_date` / `initial_time`), `standupModalViewJson` (one multiline input per question; a routine name with `<!channel>`, `&` and a `<url|label>` is escaped in the header section; the same title in `declineReasonModalViewJson` is escaped in its title section), `standupSetupModalViewJson` (8 input blocks and every element type), `cveSubscribeModalViewJson` / `cveUnsubscribeModalViewJson` (options = topic keys) — each parsed back with `GsonFactory.createSnakeCase().fromJson(json, View::class.java)`. `standupSummaryTemplate`: 60 members with 2,000-character answers → exactly 50 section blocks (title, 48 members, "…and 12 more members"), none over the section budget; routine name, question and answer with `<!channel>`, `&` and a `<url|label>` escaped while `*bold*` and `<@id>` stay markup; 30 members answering eight ~200-character questions at the modal cap → 31 sections, none cut; a 5,000-character stored answer → that section cut to the budget and ending in the truncation marker. `standupModalViewJson` answer inputs: one `max_length` for all, member line + question lines + the caps within the section budget; eight 199-character `&` questions → the floor `STANDUP_ANSWER_MIN_LENGTH` (50), and a member answering at that floor is cut by the summary (`standupAnswerMaxLengths` helper at the bottom of the file); a small routine → title, one section per member, `(blank)` and `_(no response)_` markers. `standupSummaryParts`: 30 members whose sections fill several messages → more than one part, each rendered through `standupSummaryTemplate` within 50 blocks and `MESSAGE_TEXT_BUDGET`, members in order with only their own answers, labelled `(n/total)`; a small routine or no members → one part with the plain name. `approvalTemplate` with a `<!here> R&D` profile name and a `<!channel> <https://evil.example|x>` subtitle → both escaped. A topic picker with 120 topics and 117-character labels → the first 100 offered, labels exactly 75 characters ending in `…`, keys unchanged. `meetingListFormTemplate` with a `<!channel> R&D` title and a `<https://evil.example|agenda>` decline detail → both escaped, `<@U2>` and the emphasis kept. |
| `BlockKitLimitsGuardTest.kt` | Renders through the real `SlackOutboundRenderer` → `SlackApiEventConstructor` → `ModalTemplateBuilder` and walks the JSON: at most 50 blocks (100 for views), section text ≤ 3,000 and non-blank, fields ≤ 2,000, header non-blank and ≤ 150, `max_length` ≤ 3,000, options ≤ 100 with text ≤ 75, and for messages the summed text of every `plain_text` / `mrkdwn` object ≤ `MESSAGE_TEXT_BUDGET`. Message inputs are the largest production sends: each part of a 40,000-character AI answer split by `splitMessageText`, Text replies (ephemeral, headline-less channel reply, update, response_url replacement) filling `MESSAGE_BODY_BUDGET`, a notice with a 4,000-character message and 50 mentions, an approval with markup in its subtitle, a schedule notice. Views: a subscribe modal offering 120 topics with 120-character names, a standup modal with eight 200-character questions, a decline-reason modal. `MessageContent.ErrorNotice` has no production sender (`DetailErrorAlertContext` is never built) and is not listed |
| `SlackBlockLimitsTest.kt` | `splitSectionText`: text that fits comes back as one chunk; long text splits on line boundaries within the 2,900 budget and rejoins to the original; an over-long line wraps at spaces; more chunks than `maxSections` keeps that many and ends with `TRUNCATION_MARKER`; a code block cut by a boundary is closed and re-opened, and a boundary right before the closing fence yields no empty re-opened block; no cut splits a surrogate pair or an `&amp;` entity. `splitMessageText`: a 40,000-character answer with a code block across a cut gives bodies within `MESSAGE_BODY_BUDGET` with balanced fences, a fence-free answer rejoins exactly, too many parts keeps `maxMessages` and ends with the marker. `truncateSectionText` and `truncatePlainText` |
| `SlackViewDslTest.kt` | `modal { … }` with two `plainTextInput`s serialized through `jsonMapper` and read back as a Slack SDK `View`: the one given `maxLength = 255` carries `max_length` 255 (and `multiline`), the one without has none |
| `SlackMrkdwnTest.kt` | `escapeMrkdwn`: `<!channel>` and `<url\|label>` become literal, `< 2.3.1` survives as text, `&` first, emphasis unchanged, same result as `escapeMarkup`. `neutralizeBroadcastMentions`: `<!channel>`, `<!here>`, `<!everyone>`, `<!group>`, labelled forms and `<!subteam^…>` become literal; links, `<@U…>`, emphasis and `<!date^…>` survive |
| `SlackUserProfileResolverTest.kt` | `SlackUserProfileResolver(restRequester, slackApiToken, clock, ttl, failureTtl, maxEntries)` with a MockK `RestRequester` and a local mutable `Clock`. Cases: a hit is cached (one lookup for two resolves) and the user id is passed as the `{user}` URI variable; blank display name falls back to the real name without an empty thumbnail; a failed lookup degrades to `<@id>` and is negative-cached for the 60 s failure TTL; an `ok = false` response behaves the same; an entry older than `ttl` is fetched again; at `maxEntries` an expired entry is evicted before a live one, and with none expired only the oldest entry goes Three threads missing the same user while the first lookup is held → one `users.profile.get` and the same view for all four; a lookup that throws releases its slot, so the next render asks again. |

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
- `BlockKitLimitsGuardTest` lists only inputs production can produce. When a sender's bound changes (a new cap, a
  new `MessageContent`), change its case here; do not feed inputs past what the sender can emit.
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
