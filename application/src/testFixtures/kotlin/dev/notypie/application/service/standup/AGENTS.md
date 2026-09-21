<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/service/standup

## Purpose
Builder for `NudgeCandidateSession`, the repository projection `StandupSchedulingService` reads to decide
which members still owe an answer before cutoff.

## Key Files
| File | Description |
|------|-------------|
| `NudgeCandidateSessionCreator.kt` | `createNudgeCandidateSession(sessionId = 1L, sessionUid = random, routineUid = random, cutoffAt = 2026-05-01T02:00:00Z, sentMemberIds = {}, answeredUserIds = {})` |

## For AI Agents

### Working In This Directory
- Sole consumer: `StandupSchedulingServiceTest` (nudge branch). The default `cutoffAt` matches domain's
  `createStandupSession` / `createStandupSessionDto`, so a spec can mix this projection with a session DTO
  for the same day without re-aligning times.
- `sentMemberIds` minus `answeredUserIds` is the nudge target set — set both explicitly when asserting who
  receives a DM.
- Routine, session and answer entities/DTOs come from domain fixtures
  (`domain/src/testFixtures/.../domain/standup/StandupTestFixtures.kt`); only projections owned by
  infrastructure's `repository.standup` belong here.

### Testing Requirements
No spec for the fixture itself; `StandupSchedulingServiceTest` is the coverage.

### Common Patterns
- Random `UUID`s for identity fields, fixed `Instant` literals for time fields.

## Dependencies

### Internal
None.

### External
- `dev.notypie.repository.standup.NudgeCandidateSession` from `:infrastructure`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
