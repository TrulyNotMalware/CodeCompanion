<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src/testFixtures/kotlin/dev/notypie/domain/standup

## Purpose
Builders for the standup lane in two flavours: aggregate entities (`Routine`, `RoutineMember`,
`StandupSession`, `SessionDispatch`, `StandupAnswer`) for domain entity specs, and their DTO twins
(`RoutineDto`, `RoutineMemberDto`, `StandupSessionDto`, `SessionDispatchDto`, `StandupAnswerDto`) for
application services and infrastructure renderers.

## Key Files
| File | Description |
|------|-------------|
| `StandupTestFixtures.kt` | Entities: `createRoutine(name, creatorId, commandChannel, summaryChannel, questions, triggerLocalTime = 10:00, cutoffOffset = Routine.DEFAULT_CUTOFF_OFFSET, weekdays = Routine.DEFAULT_WEEKDAYS, routineTimezone = Asia/Seoul, isActive, routineUid, members)`, `createRoutineMember(userId, userTimezone)`, `createStandupSession(routineUid, sessionDate = 2026-05-01, cutoffAt = 02:00Z, status = COLLECTING, summaryMessageTs, sessionUid, dispatches, answers)`, `createSessionDispatch(userId, dmTriggerAt = 01:00Z, dmSentAt, dmStatus = PENDING, failureReason)`, `createStandupAnswer(userId, responses, submittedAt = 01:30Z)`. DTOs: `createRoutineDto(..., cutoffOffset = 1h, weekdays = Mon–Fri, members)`, `createRoutineMemberDto(userId, userTimezone)`, `createStandupSessionDto(sessionId, sessionUid, routineUid, sessionDate, cutoffAt, status, summaryMessageTs, dispatches, answers)`, `createSessionDispatchDto(id, userId, dmTriggerAt, dmSentAt, dmStatus, failureReason)`, `createStandupAnswerDto(userId, responses, submittedAt)` |

## For AI Agents

### Working In This Directory
- Entity builders: `createRoutine` / `createRoutineMember` → domain `standup/entity/RoutineTest` and
  application `StandupRoutineSetupServiceTest`; `createStandupSession` / `createSessionDispatch` /
  `createStandupAnswer` → `standup/entity/StandupSessionTest`. Children are attached through the
  aggregate's own `addMember` / `addDispatch` / `addAnswer`, so the invariants those methods enforce apply
  to fixtures too.
- DTO builders: `createRoutineDto`, `createRoutineMemberDto`, `createStandupSessionDto`,
  `createSessionDispatchDto` → application `StandupSchedulingServiceTest`, `StandupSummaryServiceTest`;
  infrastructure `SlackOutboundStagerTest` (routine + session), `SlackOutboundRendererTest` (member),
  `OutboundMessageCodecTest` (member + `createStandupAnswerDto`).
- Entity and DTO defaults deliberately differ: `createRoutine` takes `Routine.DEFAULT_CUTOFF_OFFSET` /
  `DEFAULT_WEEKDAYS` from the aggregate, while `createRoutineDto` hardcodes 1 h and Mon–Fri; the two answer
  builders use different response texts. A third set of defaults lives in
  `../command/createCreateStandupRoutineEvent`. Check consumers before unifying.
- Session timestamps (`2026-05-01`, cutoff `02:00Z`, DM trigger `01:00Z`, answer `01:30Z`) line up with
  application's `createNudgeCandidateSession`; keep them in step if you move the day.
- The DTO builders reference `dev.notypie.domain.standup.dto.*` and the enums fully qualified instead of
  importing — harmless, but add imports if you touch the file.

### Testing Requirements
No spec for the fixtures themselves; `RoutineTest`, `StandupSessionTest` and the application/infra specs
above are the coverage.

### Common Patterns
- `.apply { children.forEach(::addX) }` after construction to populate aggregates through their public
  API.
- `Instant` literals for cross-zone fields, `LocalTime` / `ZoneId` pairs for routine trigger settings.

## Dependencies

### Internal
- `domain/standup/entity/` (+ `enums/DispatchStatus`, `SessionStatus`), `domain/standup/dto/`
- `../Constants.kt`

### External
- `java.time`, `java.util.UUID`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
