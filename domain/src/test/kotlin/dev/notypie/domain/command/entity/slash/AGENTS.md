<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/command/entity/slash (test)

## Purpose
Spec for `MeetingListRange`, the enum that turns the `list <range>` option of the meeting slash
command into a `[start, end)` window. `RequestMeetingCommand` and `RequestMeetingContext` are covered
one level up and in `../../context/`.

## Key Files
| File | Description |
|------|-------------|
| `MeetingListRangeTest.kt` | `BehaviorSpec` with a fixed `now` (2026-04-20 14:30). `dateRange(now)`: `TODAY` and `TOMORROW` are whole calendar days from start-of-day; `WEEK` is `[now, now + 7d)`; `MONTH` is a rolling `[now, now + 30d)`, not a calendar month. `parseOrNull(token)`: exact lowercase match, trims whitespace, case-insensitive, unknown / empty / blank → `null`. `DEFAULT == WEEK`. `usageTokens()` == `"today \| tomorrow \| week \| month"` in declaration order. No fixtures |

## For AI Agents

### Working In This Directory
- Adding a range means adding a `dateRange` case, a `parseOrNull` case, and updating the
  `usageTokens()` literal — the last one also appears in the `RequestMeetingContext` usage hint
  asserted by `MeetingContextTest`.
- Keep the injected `now` parameter; it is what makes this spec deterministic.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.entity.slash.MeetingListRangeTest'
```

### Common Patterns
- Destructuring `val (startAt, endAt) = range.dateRange(now = fixedNow)` and asserting both ends.

## Dependencies

### Internal
- `dev.notypie.domain.command.entity.slash.MeetingListRange`.

### External
- Kotest (`BehaviorSpec`, `shouldBe`, `shouldBeNull`), `java.time.LocalDateTime`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
