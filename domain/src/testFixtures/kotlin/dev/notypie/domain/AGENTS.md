<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src/testFixtures/kotlin/dev/notypie/domain

## Purpose
Root of the domain fixture package (`dev.notypie.domain`). Holds the `TEST_*` identity constants every
builder in this build defaults to, and one subpackage per domain lane.

## Key Files
| File | Description |
|------|-------------|
| `Constants.kt` | `TEST_APP_ID` (`A…`), `TEST_USER_ID` (`U…`), `TEST_USER_NAME`, `TEST_CHANNEL_ID` (`C…`), `TEST_CHANNEL_NAME`, `TEST_TOKEN`, `TEST_TEAM_ID` (`T…`), `TEST_TEAM_DOMAIN`, `TEST_BOT_ID` (`B…`), `TEST_BOT_TOKEN` (`xoxb-test…` placeholder), `TEST_BASE_URL` (`hooks.example.com`), `TEST_MESSAGE_TS`, `TEST_THREAD_TS`, `UNKNOWN_SUB_COMMAND_IDENTIFIER` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | Inbound command / interaction builders, field and action helpers, request-event creators, `TestCommand`, intent and event queues (see `command/AGENTS.md`) |
| `dto/` | `CommandOutput` comparison helpers and `TestValidationData` (see `dto/AGENTS.md`) |
| `meet/` | `Meeting` entity, `MeetingDto` / `MeetingParticipantDto` / `MeetingReminderDto`, meeting-lane events (see `meet/AGENTS.md`) |
| `standup/` | `Routine` / `StandupSession` entity trees and their DTO twins (see `standup/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- The Slack-shaped ids follow Slack's prefix convention (`A`, `U`, `C`, `T`, `B`) so prefix validators pass;
  keep that when adding one. Values are placeholders — never swap in a real token or workspace id.
- These constants are the shared vocabulary across all three modules: infrastructure's
  `BlockActionPayloadCreator`, `InteractionPayloadCreator`, `SlackEventCallBackRequestCreator`,
  `MeetingSchemaCreator` and application's `AppMentionPayloadCreator` all default to them. Change a value
  and every module's expectations move with it.
- `UNKNOWN_SUB_COMMAND_IDENTIFIER` exists only for `command/UnknownSubCommandDefinition.kt`, which currently
  has no consumer.

### Testing Requirements
No specs at this level; the constants are exercised through every consumer spec.

### Common Patterns
- `const val` top-level constants in the root package so a single `import dev.notypie.domain.TEST_USER_ID`
  works from any module.

## Dependencies

### Internal
None.

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
