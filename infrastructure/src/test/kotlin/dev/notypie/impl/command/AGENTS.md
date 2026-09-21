<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/command

## Purpose
Specs for the Slack inbound / outbound adapters, the Kafka publisher and the REST requester in main
`impl/command/`. All but one are plain Kotest `BehaviorSpec`s with hand-wired collaborators;
`KafkaEventPublisherTest` is the module's only `@SpringBootTest` and its only embedded-Kafka start. This
package also holds `ViewSubmissionChannelRoutingRegressionTest`, the executable contract between the modal
writers and the `view_submission` parser.

## Key Files
| File | Description |
|------|-------------|
| `KafkaEventPublisherTest.kt` | `KafkaEventPublisher.publishEvent(events)`. `@SpringBootTest` + `@EmbeddedKafka(topics = ["test-event-topic"], partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")`; injects `KafkaTemplate<String, Any>` and `EmbeddedKafkaKraftBroker`. Declares local `TestKafkaPayload : EventPayload` and `TestKafkaCommandEvent : CommandEvent`. Cases: an external event lands on the topic keyed by `idempotencyKey` and is not re-published locally; an internal event goes only to a relaxed `ApplicationEventPublisher` mock; a mixed queue splits correctly; an empty queue publishes nothing. Reads back with a throwaway consumer (`JacksonJsonDeserializer`, `trusted.packages = *`, `earliest`) |
| `RestClientRequesterTest.kt` | `RestClientRequester(baseUrl)` as `RestRequester` against the **live** `https://jsonplaceholder.typicode.com/posts`. `safeGet` / `safePost` / `safePut` / `safePatch` / `safeDelete` return `Result<ResponseEntity>`; non-safe `get` / `put` / `patch` return the body; non-safe `delete` and a `post` to `/1` throw `RestClientException`. Request / response shapes come from `testFixtures/.../dto/`. Needs outbound network |
| `SlackApiEventConstructorTest.kt` | `SlackApiEventConstructor(botToken, templateBuilder)` with a MockK `SlackTemplateBuilder` returning an empty `LayoutBlocks`. `simpleTextRequest` → `PostEventPayloadContents` with `CHANNEL_ALERT`; `simpleEphemeralTextRequest` → `EPHEMERAL_MESSAGE`, and with `targetUserId` the body keeps `channel = basicInfo.channel` and sets `user = target` (an ephemeral must not be routed into a DM); `detailErrorTextRequest` builds the `"Error : <ClassName>"` headline; `replaceOriginalText` → `ActionEventPayloadContents` with the `responseUrl`; `requestMeetingFormRequest` with null and explicit `ApprovalContents` |
| `SlackInboundMapperTest.kt` | Extension functions `InteractionPayload.toInbound()`, `toInboundCommand()`, `States.toInboundField()`, `States.toInboundAction()`. No `message_ts` → `inbound.message == null`; `container.messageTs` becomes the message handle; APPLY / REJECT / BUTTON / other → `APPROVE` / `REJECT` / `ACTIVATE` / `PASSIVE`; standup answer fields are index-sorted by the `STANDUP_ANSWER_QUESTION_PREFIX` block id and trimmed; CVE subscribe splits the parser's `"kotlin, spring"` join; every `ActionElementTypes` maps to an `InboundFieldKind` (`BUTTON` / `UNKNOWN` → `UNKNOWN`); `toInboundCommand` copies app / actor / channel identity and sets `InboundKind.INTERACTION` |
| `SlackIntentResolverTest.kt` | `SlackIntentResolver().resolveAll(intents, basicInfo)`. One `CommandIntent` → one domain event: `MeetingListRequest` → `GetMeetingListEvent`, `MeetingAttendanceUpdate` (accept and decline) → `UpdateMeetingAttendanceEvent` typed `MEETING_APPROVAL_REQUEST`, `StatusReport`, `CancelMeeting`, `RecordStandupAnswer`, `CveSubscribe` / `CveUnsubscribe` / `CveListSubscriptions` → `CveSubscriptionRequestEvent` with the matching `CveSubscriptionAction`; `Nothing` is dropped; a mixed batch keeps each intent's own `CommandDetailType` (regression for intent-routing collapse) |
| `SlackInteractionRequestParserTest.kt` | `SlackInteractionRequestParser().parseStringPayload(payload)` over JSON from `testFixtures/.../impl/command/BlockActionPayloadCreator.kt`. `block_actions`: primary → `APPLY_BUTTON`, danger → `REJECT_BUTTON`, unstyled → `BUTTON`; ephemeral payloads take type / key from the button value and `botId` from `api_app_id`, a non-primary ephemeral action → `NOTHING`; every `state.values` element type (multi select, users select, text input, date, time — including two time pickers in one block kept in insertion order and `selected_time: null` → unselected — checkboxes, unknown); users-select / multi-select / unknown actions; invalid JSON → Gson `JsonSyntaxException`; an unknown routing token → `IllegalArgumentException`; tokens after the type surface as `routingExtras`; a lone idempotency key → `NOTHING`. `view_submission`: type / key / `routingExtras` from `private_metadata` (3- and 5-token decline-reason forms, standup answer with `standup_q_<i>` block ids), synthesized `APPLY_BUTTON` action, `STATIC_SELECT` state; reschedule / add-participant recover `channel.id` from `routingExtras[1]`, decline-reason leaves it blank; `Container.messageTs` is preserved |
| `SlackOutboundRendererTest.kt` | `SlackOutboundRenderer(slackEventBuilder).render(message, basicInfo)` with a strict MockK `SlackApiEventConstructor`. Each `OutboundMessage` × `MessageContent` pair maps to one builder call: `Text` → `simpleTextRequest` (null headline → `""`, `detailType` overrides `SIMPLE_TEXT`, `threadId` → `threadTs`), `ErrorNotice` → `detailErrorTextRequest`, `Schedule` → `simpleTimeScheduleRequest`, `Form` → `simpleApprovalFormRequest`, `MeetingRequest`, `StandupSummary`, `Ephemeral` (recipient / null / detailType), `MeetingList` → `getMeetingListFormRequest`, `Approval` → `simpleApplyRejectRequest` with `subTitle` as `routingExtras` (blank filtered), `Notice` → `"[Notice] <@U1> <@U2> ..."` under headline `"Notice!"`, `UpdateMessage` → `updateNoticeMessageRequest`, `ReplaceMessage` → `replaceOriginalText`; `OpenModal` and `DirectMessage` throw `IllegalStateException` |
| `SlackOutboundStagerTest.kt` | `SlackOutboundStager(slackEventBuilder, standupRepository).stage(message, basicInfo)`. Non-modal families are wrapped as `OutboundMessageEnqueued` without touching the builder (the strict mock proves it). `OpenModal` forms delegate to `openRescheduleMeetingModalRequest`, `openAddParticipantModalRequest`, `openStandupSetupModalRequest`, `openStandupModalRequest` (loads routine + session; a missing session → `null`), `openCveSubscribeModalRequest`, `openCveUnsubscribeModalRequest`, `openDeclineReasonModalRequest`; a blank trigger id yields `null` for every form **except `DeclineReason`**, which has no guard and passes `""` through |
| `ViewSubmissionChannelRoutingRegressionTest.kt` | **Regression guard — never delete.** Drives the real `ModalTemplateBuilder` for the reschedule, add-participant, standup-setup, standup-answer and decline-reason modals, extracts `private_metadata` from the emitted JSON through the Slack SDK `View` model (never hand-built), feeds it to the real parser, then runs the real `InteractionCommand.handleEvent()` / `drainIntents()` and asserts the recovered channel / `routingExtras` end to end. The last block is the negative control: it reverses the writer's trailing tokens and proves the misroute (requester and channel swap), so the positive cases are order-sensitive |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `slack/` | `ElementTest` (rich-text `text` polymorphic deserializer) and `SlackMentionMapperTest` (app_mention flattening) (see `slack/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Keep `ViewSubmissionChannelRoutingRegressionTest` green by changing writer and reader together.** A new
  token in a modal's `private_metadata`, or an index change in a domain context that reads `routingExtras`,
  must land on both sides and get a new `given` here extracted from the real modal JSON. Never add a
  hand-typed metadata string to that spec.
- **Renderer vs stager split**: modal opening is staged eagerly (the trigger id expires), everything else is
  wrapped transport-neutral and rendered at deliver time. A new non-modal `OutboundMessage` family needs a
  `SlackOutboundRendererTest` case (the stager wraps it generically) plus a codec case in
  `repository/outbox/`; a new `ModalForm` needs a `SlackOutboundStagerTest` case.
- **Strict mocks are the assertion.** `SlackOutboundStagerTest` relies on an unstubbed builder throwing to
  prove non-modal messages never render; do not switch it to `relaxed = true`.
- `KafkaEventPublisherTest` is the only broker start in the module and takes seconds; add Kafka cases inside
  it rather than creating a second `@EmbeddedKafka` context.
- `RestClientRequesterTest` is the module's only network-dependent spec; it fails offline and should not
  grow — stub with the JDK `HttpServer` like `impl/agent` and `impl/cve` instead.
- Payload JSON and typed payloads come from `testFixtures/.../impl/command/`; keep inline JSON out of the
  specs (`SlackInteractionRequestParserTest` has one inline two-picker block; treat that as the ceiling).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.*'
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.ViewSubmissionChannelRoutingRegressionTest'
```
Spring: `KafkaEventPublisherTest` only (boots `TestApplication`; the `spring.kafka.*` serializer keys in
`src/test/resources/application.yaml` apply). Network: `RestClientRequesterTest` only. Everything else is a
plain unit spec.

### Common Patterns
- `BehaviorSpec` with `given` / `` `when` `` / `then`; `mockk<T>()` strict, `every { } returns`,
  `verify(exactly = 1) { }`, `slot()` captures for headlines / routing lists.
- Identity constants (`TEST_APP_ID`, `TEST_BOT_TOKEN`, `TEST_BASE_URL`, ...) and `createCommandBasicInfo()`
  from `:domain` test fixtures; `createSendSlackMessageEvent` / `createOpenViewEvent` from
  `testFixtures/.../impl/command/event/`.
- Named arguments on every call, including inside `verify` blocks.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/` (+ `event/`, `slack/`) — classes under test
- `infrastructure/src/main/kotlin/dev/notypie/templates/` — `ModalTemplateBuilder`, `*ModalIds`, `ButtonType`
- `infrastructure/src/testFixtures/kotlin/dev/notypie/{impl/command, dto}/`
- `domain/src/testFixtures/kotlin/dev/notypie/domain/` — constants, `createCommandBasicInfo`, DTO creators
- `domain/command` — `InteractionCommand`, `CommandIntent`, `OutboundMessage`, inbound model, events

### External
Kotest 6, MockK, `spring-boot-starter-test`, `spring-kafka-test` (`EmbeddedKafkaKraftBroker`), Slack SDK
model + Gson, Jackson 3.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
