<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/meet/dto

## Purpose
Read-side projections of the meeting aggregate. Repositories in `infrastructure` map JPA rows into these
`data class`es so application services and Slack renderers can work with plain values that carry no
entity invariants and no mutable collections.

## Key Files
| File | Description |
|------|-------------|
| `MeetingDto.kt` | `MeetingDto(meetingId, meetingUid, idempotencyKey, creator, title, reason, startAt, endAt?, participants, isCanceled)`; `MeetingParticipantDto(userId, isAttending, absentReason = RejectReason.ATTENDING, absentReasonDetail?)`; `internal data class MeetingListDto(meetings)` — unreferenced anywhere in the repo |
| `MeetingReminderDto.kt` | `MeetingReminderDto(id, meetingId, offsetMinutes, scheduledAt: Instant, sentAt?, status: MeetingReminderStatus, failureReason?)` — the scheduler's view of one reminder batch |

## For AI Agents

### Working In This Directory
- These are constructed only in `infrastructure/repository/meeting/schema/`: `MeetingSchema.toMeetingDto()`
  and `MeetingReminderSchema.toMeetingReminderDto()`. `toMeetingDto()` hard-codes `reason = ""`, so
  `MeetingDto.reason` is never populated from storage today — do not build UI logic on it.
- `MeetingDto.endAt` is nullable while `Meeting.endAt` is non-null (defaults to `startAt + 1h`); the DTO
  mirrors the nullable column, so consumers must handle `null` themselves.
- `MeetingDto` crosses into the command package as `MessageContent.MeetingList(meetings, currentUserId)`
  (`command/outbound/MessageContent.kt`). That content is staged through the outbox and serialized by
  `infrastructure/repository/outbox/OutboundMessageCodec.kt`, so renaming or removing a field here
  changes the persisted outbox payload — treat it as a wire format, not a private DTO.
- `isAttending` and `absentReason` are both stored; `RejectReason.ATTENDING` is the "not absent" value.
  Keep them consistent when constructing (`isAttending = false` implies a non-`ATTENDING` reason).
- `MeetingReminderDto` reaches the application layer through the result type declared in
  `infrastructure/repository/meeting/MeetingReminderRepository.kt` (`reminder: MeetingReminderDto`); the
  scheduler never touches the `MeetingReminder` entity.
- `MeetingListDto` is `internal` and has no callers — delete it or promote it deliberately; do not add
  a second list wrapper next to it.
- Must not import `dev.notypie.domain.command` (guard-enforced one-way edge).

### Testing Requirements
No domain spec targets this package (pure data, no behaviour). The layering guard covers it:
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.architecture.DomainLayeringGuardTest'
```
Build instances with `createMeetingDto` / `createMeetingParticipantDto`
(`domain/src/testFixtures/kotlin/dev/notypie/domain/meet/MeetingDtoCreator.kt`) and
`createMeetingReminderDto` (`MeetingReminderDtoCreator.kt`). Behaviour over these DTOs is specified
downstream: `:infrastructure:test --tests '*ModalTemplateBuilderTest'` and
`:application:test --tests '*MeetingReminderSchedulingServiceTest'`.

### Common Patterns
- `data class` with defaults for optional fields; no `init` validation — invariants live on the entities.
- `LocalDateTime` for user-facing meeting times, `Instant` for the absolute reminder fire time.

## Dependencies

### Internal
- `meet/entity/RejectReason`, `meet/entity/enums/MeetingReminderStatus`

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
