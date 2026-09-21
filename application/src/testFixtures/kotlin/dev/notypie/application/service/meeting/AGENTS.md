<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/service/meeting

## Purpose
Builders for the scheduling side of the meeting lane: the `AgendaItem` the daily-agenda message renders,
and the two repository projections (`AgendaCandidateMeeting`, `ReminderCandidateMeeting`) the scheduling
services read.

## Key Files
| File | Description |
|------|-------------|
| `AgendaItemCreator.kt` | `createAgendaItem(startAt = 2026-05-04T10:00, title = "Sprint Planning")`, `createAgendaCandidateMeeting(meetingId = 1L, title, startAt = 2026-05-04T10:00, attendingUserIds = ["U_A"])`, `createReminderCandidateMeeting(meetingId = 1L, startAt (required), attendingUserIds = ["U_A"])` |

## For AI Agents

### Working In This Directory
- Consumers: `DailyAgendaMessageBuilderTest` (`createAgendaItem`), `DailyAgendaSchedulingServiceTest`
  (`createAgendaCandidateMeeting`), `MeetingReminderSchedulingServiceTest`
  (`createReminderCandidateMeeting`).
- `createReminderCandidateMeeting.startAt` has no default on purpose: reminder eligibility is computed
  relative to the spec's fixed clock, so the caller must place the start time in relation to it.
- `AgendaCandidateMeeting` / `ReminderCandidateMeeting` are infrastructure projection types
  (`dev.notypie.repository.meeting`); `AgendaItem` is this module's own value in `service/meeting/`.
- Full `MeetingDto` / `Meeting` builders are domain fixtures
  (`domain/src/testFixtures/.../domain/meet/`) — do not duplicate them here.

### Testing Requirements
No spec for the fixture itself; the three scheduling/message-builder specs are the coverage.

### Common Patterns
- Named-argument builders with deterministic `LocalDateTime` literals (never `now()`), so rendered agenda
  text is byte-stable across runs.

## Dependencies

### Internal
- `dev.notypie.application.service.meeting.AgendaItem` (main)

### External
- `dev.notypie.repository.meeting.AgendaCandidateMeeting`, `ReminderCandidateMeeting` from `:infrastructure`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
