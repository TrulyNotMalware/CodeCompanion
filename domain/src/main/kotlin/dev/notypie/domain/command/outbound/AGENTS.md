<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/outbound

## Purpose
Everything a context can *say*, with no Slack type: message shapes (`OutboundMessage`), their content
(`MessageContent`), modal descriptions (`ModalForm`), addressing values, and the two opaque handles a
transport needs to open a modal or replace a message. `OutboundMessageStager` is the port the transport
adapter implements to turn these into staged events.

## Key Files
| File | Description |
|------|-------------|
| `OutboundMessage.kt` | `sealed interface OutboundMessage : CommandEffect` — `ChannelMessage(target, content, detailType?, threadId?)`, `Ephemeral(target, recipient?, content, detailType?)`, `DirectMessage(recipient, content)`, `UpdateMessage(ref, content, detailType)`, `ReplaceMessage(handle, content)`, `OpenModal(handle, form)`, `Approval(target, recipient?, approval, routingExtras)`, `Notice(target, mentions, message)` |
| `MessageContent.kt` | `sealed interface` — `Text(headline?, markdown)`, `ErrorNotice(className, message, details?)`, `Schedule(headline, info: TimeScheduleInfo)`, `Form(headline, fields, reason?, approval?)`, `MeetingRequest(approval?)`, `MeetingList(meetings: List<MeetingDto>, currentUserId)`, `StandupSummary(routineName, sessionDate, members, answers, questions)` |
| `ModalForm.kt` | `sealed interface` — `Reschedule`, `AddParticipant` (both `meetingUid`, `requesterId`, `channel`), `StandupFill(sessionUid, routineUid, requesterId, originNotice)`, `StandupSetup(creatorId, commandChannel)`, `DeclineReason(meetingIdempotencyKey, participantUserId, meetingTitle, originNotice?)`, `CveSubscribe(topics)`, `CveUnsubscribe(topics)`; `TopicOption(key, label)` |
| `OutboundTargets.kt` | Value classes `ConversationTarget(id)`, `UserRef(id)`; `MessageRef(conversation, messageId)` |
| `InteractionHandles.kt` | Value classes `ModalOpenHandle(raw)` (Slack `trigger_id`, ~3 s lifetime) and `ResponseReplaceHandle(raw)` (Slack `response_url`) |
| `OutboundMessageStager.kt` | `stage(message, basicInfo): CommandEvent<EventPayload>?` — null when the message produces no event |

## For AI Agents

### Working In This Directory
- The only `OutboundMessageStager` is `infrastructure/impl/command/SlackOutboundStager.kt`; rendering
  goes through `OutboundRenderer.kt` and `infrastructure/templates/`. Staged messages are persisted by
  `infrastructure/repository/outbox/OutboundMessageCodec.kt`, which registers every `MessageContent`
  variant by name in `@JsonSubTypes`. A new variant needs the codec entry and a renderer branch; a
  renamed variant or field changes stored outbox rows (`OutboundMessageCodecTest` pins the format).
- `detailType` is optional on `ChannelMessage` / `Ephemeral` (the content family's default applies) but
  required on `UpdateMessage`, because a `chat.update` must route the next click back to a context.
- `OpenModal` must be queued in the same request that received the trigger; the handle expires in
  about three seconds. `ModalForm` variants carry `channel` / `originNotice` precisely because a later
  `view_submission` has no channel of its own.
- `threadId` non-null on `ChannelMessage` posts a threaded reply; `AgentConverse` replies use the thread
  root as the conversation id.
- `Ephemeral.recipient = null` is the common case (`CommandContext.errorEphemeral`); infrastructure
  resolves the addressee. `Approval.routingExtras` are appended to the button value after
  `<idempotencyKey>,<detailType>` — see `command/inbound/AGENTS.md` for the read side.
- `MeetingList` and `StandupSummary` embed `meet/dto` and `standup/dto` types — together with the
  intents' `RejectReason` this is the whole `command → meet/standup` edge. Do not add entity types here;
  DTOs only.
- Value classes are `@JvmInline` single-`String` wrappers; keep them that way so the codec and the
  guard test (`responseUrl` / `triggerId` identifiers are forbidden) stay happy.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.outbound.OutboundMessageTest'
```
Every context spec asserts on the `OutboundMessage`s it drains. Rendering and staging are pinned
downstream: `:infrastructure:test --tests '*SlackOutboundStagerTest' --tests '*SlackOutboundRendererTest'
--tests '*OutboundMessageCodecTest'`.

### Common Patterns
- Sealed interfaces for exhaustive `when` in the stager and renderer; `data class` per variant.
- KDoc on each variant says what it maps to on Slack ("On Slack this maps to a `trigger_id`") without
  naming a Slack type in code.

## Dependencies

### Internal
- `command/dto/CommandBasicInfo`, `command/dto/modals`, `command/entity/CommandDetailType`,
  `command/entity/event` (`CommandEvent`, `EventPayload`), `command/intent/CommandEffect`,
  `meet/dto/MeetingDto`, `standup/dto` (`RoutineMemberDto`, `StandupAnswerDto`)

### External
`java.time.LocalDate`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
