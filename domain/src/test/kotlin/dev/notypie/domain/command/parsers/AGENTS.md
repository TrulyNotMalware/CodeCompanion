<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

# domain/command/parsers (test)

## Purpose
Routing specs for the two `ContextParser` implementations in main `command/entity/parsers/`: which
`CommandContext` a mention or an interaction resolves to, and — for mentions — whether the actor's
`UserRole` is allowed to reach it. The rich-text flattening that produces the mention tokens lives in
infrastructure (`SlackMentionMapperTest`); here a `MentionInvocation` is fed in directly.

## Key Files
Both specs are Kotest `BehaviorSpec`s.

| File | Description |
|------|-------------|
| `AppMentionContextParserTest.kt` | `AppMentionContextParser(commandData, mention, idempotencyKey, intents, actorRole)`. **Routing** (as `ADMIN`): `notice` → `NoticeContext`, `approval` → `ApprovalFormContext`, `help` → `TextResponseContext`, `status` → `StatusContext`, `ask …` → `AgentChatContext` with the keyword stripped, `message` as thread anchor, and an enclosing `thread` winning over `message`; unknown free text → `AgentChatContext` with the full text as prompt; no command structure → `TextResponseContext`; command structure but zero tokens → `IllegalArgumentException`. **Authorization**: `USER` is denied `status`, `notice`, and free text with the exact denial strings, but `help` routes; `AI_USER` may `ask` but not `status`; `DEVELOPER` may `notice` and `ask` but not `grant` or `cve`. **Role management**: `grant @user <role>` → `GrantRole`, `revoke @user` → `RevokeRole`, `roles` → `ListRoles`; missing mention, extra tokens, or unknown role → `GRANT_USAGE` / `REVOKE_USAGE` / `ROLES_USAGE` text instead of an intent. **CVE ops**: `cve topics` → `CveListTopics`, `cve topic activate\|deactivate <key>` → `CveSetTopicActive` (key lower-cased), `cve retry all` → `CveRetryDeadLetters`, `cve retry <id>` → `CveRetryDeadLetter`; no sub-command, unknown sub-command, or non-numeric id → `CVE_USAGE`. Uses `createIntentQueue`, `createMentionInboundCommand`, `TEST_MESSAGE_TS`, `TEST_THREAD_TS`, `TEST_USER_ID`, `TEST_USER_NAME` |
| `InteractionContextParserTest.kt` | `InteractionContextParser.parseContext` by `CommandDetailType`: `APPROVAL_REQUEST` → `ApprovalFormContext`, `APPROVAL_CALLBACK` → `ApprovalCallbackContext`, `MEETING_CREATE_REQUEST` → `RequestMeetingContext`, `MEETING_APPROVAL_REQUEST` → `MeetingApprovalResponseContext`, anything else (`SIMPLE_TEXT`) → `EmptyContext`; a submission-bearing interaction → its leaf (submission routing wins); a SUBMIT detail type without a submission → `IgnoredSubmissionContext`. Uses `createInboundInteraction`, `createInteractionResponseInboundCommand`, `createIntentQueue` |
| `SubmissionRouterTest.kt` | `SubmissionRouter.route` (Phase 11): every variant reaches its own leaf regardless of the envelope detail type (variant wins), parser rejection → `IgnoredSubmissionContext` + `PARSE_REJECTED` observed, missing submission on a SUBMIT route → `MISSING_SUBMISSION` observed, non-submission interactions → `null` (detail-type routing continues) |

## For AI Agents

### Working In This Directory
- A new mention keyword needs three things here: a routing case (as `ADMIN`), an authorization case
  for the first role that must be denied, and — if it carries arguments — the usage-text fallbacks.
- Denial strings are asserted verbatim. Change them in `AppMentionContextParser` and here together.
- The routing cases share one top-level `intents` queue but never drain it; cases that call
  `runCommand()` create their own queue (`askIntents`, `fallbackIntents`, ...). Follow that split so
  effect assertions stay isolated.
- When a new `CommandDetailType` gets a branch in `InteractionContextParser`, add its `when` to
  `InteractionContextParserTest` — the parser spec is the cheapest place to catch a mis-wired enum.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.parsers.*'
```

### Common Patterns
- Local helpers inside the spec body (`createParser(...)`, `mentionOf(...)`, `deniedMarkdown(...)`,
  `firstEffectOf(...)`, `usageOf(...)`) keep each `when` to one line of intent; reuse them rather
  than re-inlining parser construction.
- Routing asserted with `shouldBeInstanceOf<XContext>()`; effects asserted by calling `runCommand()`
  on the returned context and reading `queue.snapshot().first()`.

## Dependencies

### Internal
- `dev.notypie.domain.command.entity.parsers.{AppMentionContextParser, InteractionContextParser}` and
  their `*_USAGE` constants; `entity.context.*` / `entity.context.form.*` (routing targets);
  `command.authorization.UserRole`; `command.intent.{CommandIntent, IntentQueue}`;
  `command.inbound.{MentionInvocation, MessageHandle}`; `command.outbound.{OutboundMessage, MessageContent}`.
- `testFixtures` — `command/InboundCommandCreator.kt`, `command/InboundInteractionInputCreator.kt`,
  `command/MockEventBuilderCreator.kt`, `Constants.kt`.

### External
- Kotest (`BehaviorSpec`, `shouldThrow`, `shouldBe`, `shouldBeInstanceOf`), `java.util.UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
