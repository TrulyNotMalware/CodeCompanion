<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-22 -->

# domain/command/outbound

## Purpose
Everything a context can *say*, with no Slack type: message shapes (`OutboundMessage`), their content
(`MessageContent`), modal descriptions (`ModalForm`), addressing values, and the two opaque handles a
transport needs to open a modal or replace a message. `OutboundMessageStager` is the port the transport
adapter implements to turn these into staged events.

## Key Files
| File | Description |
|------|-------------|
| `OutboundMessage.kt` | `sealed interface OutboundMessage : CommandEffect` — `ChannelMessage(target, content, detailType?, threadId?, fallback: DirectReply? = null)`, `Ephemeral(target, recipient?, content, detailType?, fallback: DirectReply? = null)` (`fallback` is the requester's DM the relay stages when Slack refuses the reply's channel, see `DirectReply.kt`), `DirectMessage(recipient, content)`, `UpdateMessage(ref, content: MessageContent.Text, detailType)`, `ReplaceMessage(handle, content: MessageContent.Text, fallback: Ephemeral? = null)` (Text by type since B1 — both rewrite markdown in place, so non-Text content is unrepresentable; `fallback` is what the relay sends instead when Slack refuses the replace, e.g. an expired or used-up handle — the `/meetup list` close uses it to fall back to the host's ephemeral), `OpenModal(handle, form)`, `Approval(target, recipient?, approval, routingExtras)`, `Notice(target, mentions, message)` |
| `DirectReply.kt` | `data class DirectReply(recipient: UserRef, content: MessageContent.Text)` — a DM to one user, not an `OutboundMessage`: it is never staged on its own, has no codec subtype and cannot carry a thread, a detail type or non-Text content. `channelRefusedNotice(channel, recipient)` builds the neutral notice for replies whose content belongs in the channel (the `/meetup` form, the `/meetup list` list); `askRefusedNotice(channel, recipient, prompt)` is the `/ask` question's notice: it says the question was *not* answered (a refused question row publishes no `MessageDeliveredEvent`, so no agent turn starts), then a blank line and the prompt escaped with `escapeMarkup()`, split on every Unicode line break with `domain/common/splitLineBreaks()` and quoted line by line with `> `, the escaped prompt cut at 1,500 characters with `domain/common/takeEntitySafe` plus `…` (the cut never splits a surrogate pair or an `&amp;`-style entity); `directFallbackOf(channel, recipient, content)` copies a Text reply with one leading line and the original headline. All three word themselves by `ConversationTarget.kind()`: `CHANNEL` names the conversation as an escaped `<#C…>` mention (`I couldn't reply in <#C>: the post there was refused. If I'm not a member of that channel, invite me and try again.` / `_(Sent here because my reply in <#C> was refused.)_`), so a forged id cannot break out of the mention; `DIRECT` and `GROUP` never render the id (the transport shows `<#D…>` as the other member's name) and say "that direct message" / "that conversation" instead, with the reason `I can only post in conversations I'm part of` (2026-10-20 live finding, decision #49 in `docs/wiki/decisions.md`). `GROUP` says "that conversation" because a `G` id is either a group DM or a private channel created before March 2021 (both in Slack's docs). No wording sends the requester to the bot's own DM ("message me here", "ask me here"): production keeps the app's Messages tab read-only (`docs/slack-app-manifest.yaml` `messages_tab_read_only_enabled: true`) and subscribes only `app_mention`, so nothing typed there reaches the bot; the notices point at running the command again in a channel the bot has joined, or adding the bot to the conversation. None of them names the transport (domain text stays transport-neutral); the one piece of transport id knowledge is `kind()` reading the id prefix. Producers call these three, never hand-write the text |
| `MessageContent.kt` | `sealed interface` — `Text(headline?, markdown)`, `ErrorNotice(className, message, details?)`, `Schedule(headline, info: TimeScheduleInfo)`, `MeetingRequest(approval?)`, `MeetingList(meetings: List<MeetingDto>, currentUserId)`, `StandupSummary(routineName, sessionDate, members, answers, questions)`, `AgentQuestion(prompt, requesterName, channelName)` (the `/ask` question) |
| `ModalForm.kt` | `sealed interface` — `Reschedule`, `AddParticipant` (both `meetingUid`, `requesterId`, `channel`, `listHandle: ResponseReplaceHandle?` — the `/meetup list` message the modal was opened from, ferried to the submission so its success can close the list), `StandupFill(sessionUid, routineUid, requesterId, originNotice)`, `StandupSetup(creatorId, commandChannel)`, `DeclineReason(meetingIdempotencyKey, participantUserId, meetingTitle, originNotice?)`, `CveSubscribe(topics)`, `CveUnsubscribe(topics)`; `TopicOption(key, label)` |
| `OutboundTargets.kt` | Value classes `ConversationTarget(id)`, `UserRef(id)`; `MessageRef(conversation, messageId)`; `enum class ConversationKind { CHANNEL, DIRECT, GROUP }` and `ConversationTarget.kind()` — first character of the id, case-sensitive: `D` → `DIRECT`, `G` → `GROUP` (a group DM or a private channel created before March 2021, both in Slack's docs, hence the "that conversation" wording), anything else (lower-case `d` / `g` and an empty id included) → `CHANNEL`; this id-prefix reading is the outbound package's one piece of transport id knowledge. A newer private channel is `C` (the live `C0CA3FYCAT1`); Slack's docs show a `G` group DM (the legacy MPIM object example `G024BE91L`) and never a `C` one, so whether a group DM can carry a `C` id is neither documented nor measured (a `C` one would get the channel wording), and the documented signal is `is_mpim` from `conversations.info` (decision #49) |
| `InteractionHandles.kt` | Value classes `ModalOpenHandle(raw)` (Slack `trigger_id`, ~3 s lifetime) and `ResponseReplaceHandle(raw)` (Slack `response_url`; Slack accepts a handle for about 30 minutes and five uses). `ResponseReplaceHandle.toString()` is `ResponseReplaceHandle(redacted)` (B3, 2026-10-15): posting through it needs only the URL — Slack documents a plain POST to it, so needing no token is an inference — and no data class that carries one — `ReplaceMessage`, `ModalForm.Reschedule` / `AddParticipant`, `CommandIntent.CancelMeeting` / `RescheduleMeeting` / `AddParticipant`, `inbound/InboundSubmission.RescheduleMeeting` / `AddParticipant` and the parsed submissions — prints it, nullable properties included; transports read `.raw`. `ModalOpenHandle` keeps the generated `toString`: a trigger id opens nothing without the bot token and expires within seconds. `internal replaceHandleOrNull(raw)` — blank → `null`, the domain's conversion from an inbound reply handle; every caller is in `command/entity/context/form`. The `/meetup list` handle a modal ferries arrives already built: the infrastructure mapper (`SlackInboundMapper`) applies the same blank → `null` rule, so `replaceHandleOrNull` stays `internal` (2026-10-20) |
| `OutboundMessageStager.kt` | `stage(message, basicInfo): CommandEvent<EventPayload>?` — null when the message produces no event; `stageInOrder(messages, basicInfo): CommandEvent<EventPayload>` — one event for several messages that must post one after another (a reply split past one message's budget): the transport sends each only after the previous one went out |

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
- **Slash replies carry a `fallback`** (B-2, 2026-10-14): a slash command can run in a channel the bot has not
  joined, where `chat.postEphemeral` / `chat.postMessage` fail with `not_in_channel` / `no_permission`. Every
  slash reply sets `fallback`: the contexts in `entity/context/form/AGENTS.md`, `Command.replyToSlashFailure`, and
  the application services that answer a slash command in its channel (`service/standup`, `service/meeting`
  `getMeetingListEvent`, `service/calendar`, `service/cve/subscription`). The one exception is the
  `/calendar connect` pointer, whose consent link is already a DM. Mention-path and interaction replies set none
  (the bot is in those channels). A row with a `fallback` was stamped schema V6 before
  2026-10-20; every row is V7 since (`infrastructure/repository/outbox/schema/AGENTS.md`, `docs/wiki/decisions.md` #50).
- `Ephemeral.recipient = null` is the common case (`CommandContext.errorEphemeral`); infrastructure
  resolves the addressee. `Approval.routingExtras` are appended to the button value after
  `<idempotencyKey>,<detailType>` — see `command/inbound/AGENTS.md` for the read side.
- `MeetingList` and `StandupSummary` embed `meet/dto` and `standup/dto` types — together with the
  intents' `RejectReason` this is the whole `command → meet/standup` edge. Do not add entity types here;
  DTOs only.
- Value classes are `@JvmInline` single-`String` wrappers; keep them that way so the codec and the
  guard test (`responseUrl` / `triggerId` identifiers are forbidden) stay happy. The codec writes the unboxed
  `raw`, never `toString()`, so the redacted `ResponseReplaceHandle.toString()` leaves stored rows unchanged
  (`OutboundMessageCodecTest` pins the wire shape). Because every handle prints the same, a failing `shouldBe` on two
  different handles (or on data classes holding them) shows identical text — compare `.raw` to see the values.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.outbound.OutboundMessageTest'
```
Every context spec asserts on the `OutboundMessage`s it drains. Rendering and staging are pinned
downstream: `:infrastructure:test --tests '*SlackOutboundStagerTest' --tests '*SlackOutboundRendererTest'
--tests '*OutboundMessageCodecTest'`.

### Common Patterns
- Sealed interfaces for exhaustive `when` in the stager and renderer; `data class` per variant.
- Variants never name a Slack type; the Slack mapping lives in infrastructure's `SlackOutboundRenderer`.

## Dependencies

### Internal
- `command/dto/CommandBasicInfo`, `command/dto/modals`, `command/entity/CommandDetailType`,
  `command/entity/event` (`CommandEvent`, `EventPayload`), `command/intent/CommandEffect`,
  `meet/dto/MeetingDto`, `standup/dto` (`RoutineMemberDto`, `StandupAnswerDto`)

### External
`java.time.LocalDate`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
