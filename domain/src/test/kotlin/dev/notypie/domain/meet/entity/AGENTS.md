<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/meet/entity (test)

## Purpose
Invariants of the `Meeting` aggregate and its `Member` value: what a valid meeting looks like, every
limit the entity enforces in its `init` block, and the `addParticipant` cap.

## Key Files
| File | Description |
|------|-------------|
| `MeetingTest.kt` | `BehaviorSpec` over `Meeting` and `Member`. Valid construction: `host` is the publisher with `isHost = true`, `memberSnapshot()` / `memberIdSnapshot()` reflect `members`, `isCanceled` defaults to `false`, `meetingUid` is generated, distinct per instance, and honoured when supplied; `endAt` defaults to `startAt + 1h` (built directly, not through the fixture, to hit the entity default). Rejections, all `ValidationExceptionWithName`: blank `publisher`, blank `title`, `title` > `MAX_TITLE_LENGTH`, `reason` > `MAX_REASON_LENGTH`, members > `MAX_PARTICIPANTS`, `startAt` in the past, `endAt` before `startAt`. `addParticipant` within the cap and one past `MAX_PARTICIPANTS`. `Member`: blank `userId` rejected, `isGuest` / `isHost` flags. Uses `createMeeting` |

## For AI Agents

### Working In This Directory
- Limits are referenced as `Meeting.MAX_*` constants, never as literals — keep it that way so a limit
  change in the entity moves the test with it.
- `createMeeting()` defaults `startAt` to `now() + 1 day`, which is what keeps the "not in the past"
  rule satisfied; pass explicit `startAt` / `endAt` for anything time-sensitive.
- `RequestMeetingContext` (see `../../command/context/`) renders the entity's own validation message
  to the user, so wording changes here surface in `MeetingContextTest` too.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.meet.entity.MeetingTest'
```

### Common Patterns
- `shouldThrow<ValidationExceptionWithName> { createMeeting(...) }` with exactly one field off-spec per
  `when`, so the failing rule is unambiguous.
- Snapshot accessors (`memberSnapshot()`) are asserted instead of reaching into the backing collection.

## Dependencies

### Internal
- `dev.notypie.domain.meet.entity.Meeting`, `Member`; `dev.notypie.domain.common.error.ValidationExceptionWithName`.
- `testFixtures` — `meet/MeetingTestFixtures.kt` (`createMeeting`).

### External
- Kotest (`BehaviorSpec`, `shouldThrow`, `shouldBe`, `shouldNotBe`), `java.time`, `java.util.UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
