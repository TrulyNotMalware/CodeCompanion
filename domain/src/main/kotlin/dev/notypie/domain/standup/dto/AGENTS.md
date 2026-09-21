<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/standup/dto

## Purpose
Read-side projections of the standup aggregates. A `RoutineDto` is what the scheduler iterates to open
sessions; a `StandupSessionDto` carries dispatches and answers together so the summary renderer needs a
single repository round-trip.

## Key Files
| File | Description |
|------|-------------|
| `RoutineDto.kt` | `RoutineDto(routineId, routineUid, name, creatorId, commandChannel, summaryChannel, questions, triggerLocalTime, cutoffOffset, weekdays, routineTimezone, isActive, members)`; `RoutineMemberDto(userId, userTimezone: ZoneId)` |
| `StandupSessionDto.kt` | `StandupSessionDto(sessionId, sessionUid, routineUid, sessionDate, cutoffAt, status, summaryMessageTs?, dispatches, answers)`; `SessionDispatchDto(id, userId, dmTriggerAt, dmSentAt?, dmStatus, failureReason?)`; `StandupAnswerDto(userId, responses, submittedAt)` |

## For AI Agents

### Working In This Directory
- Built in `infrastructure/repository/standup/schema/RoutineSchema.kt` and `StandupSessionSchema.kt`
  (plus `StandupRepositoryImpl` for a bare `SessionDispatchDto`); consumed by
  `application/service/standup/StandupSchedulingService.kt` and the Slack renderers in
  `infrastructure/templates/`.
- `RoutineMemberDto` and `StandupAnswerDto` are embedded in `MessageContent.StandupSummary(routineName,
  sessionDate, members, answers, questions)` (`command/outbound/MessageContent.kt`) and serialized into
  the outbox by `infrastructure/repository/outbox/OutboundMessageCodec.kt`
  (`OutboundMessageCodecTest` pins the shape). Field renames here change persisted payloads.
- `StandupAnswerDto.responses` is positional: the renderer pairs `questions[i]` with `responses[i]`
  with no question id. Never reorder responses or questions independently.
- `SessionDispatchDto.id` is the JPA row id the scheduler hands back to `claimDispatch` / `markSent` /
  `markFailed` in `JpaSessionDispatchRepository`; it is not a business key. `userId` is the natural key.
- `routineTimezone` decides which calendar day a session belongs to; each `RoutineMemberDto.userTimezone`
  decides when that member's DM fires. Do not collapse the two when adding fields.
- Must not import `dev.notypie.domain.command` (guard-enforced one-way edge).

### Testing Requirements
No domain spec targets this package (pure data). The layering guard covers it:
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.architecture.DomainLayeringGuardTest'
```
Build instances with `createRoutineDto`, `createRoutineMemberDto`, `createStandupSessionDto`,
`createSessionDispatchDto`, `createStandupAnswerDto`
(`domain/src/testFixtures/kotlin/dev/notypie/domain/standup/StandupTestFixtures.kt`). Behaviour over
these DTOs lives in `:application:test --tests '*StandupSchedulingServiceTest'`,
`'*StandupSummaryServiceTest'` and `:infrastructure:test --tests '*OutboundMessageCodecTest'`.

### Common Patterns
- `data class` per projection, nested DTOs as `List`, nullable only where the column is nullable.
- `LocalTime` + `ZoneId` + `DayOfWeek` + `Duration` for recurring config; `Instant` for `cutoffAt`,
  `dmTriggerAt`, `dmSentAt`, `submittedAt`; `LocalDate` for `sessionDate`.

## Dependencies

### Internal
- `standup/entity/enums/DispatchStatus`, `standup/entity/enums/SessionStatus`

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
