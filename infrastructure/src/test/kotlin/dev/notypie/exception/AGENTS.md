<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/test/kotlin/dev/notypie/exception

## Purpose
Mirror of main `exception/`. Contains one file, and that file is an empty placeholder.

## Key Files
| File | Description |
|------|-------------|
| `DatabaseExceptionTest.kt` | **Placeholder — exercises nothing.** A `BehaviorSpec` whose only content is an empty `given("Nullable Schema") { }`; there is no `` `when` ``/`then`, so Kotest reports zero tests for it. Do not read its presence as coverage of `DatabaseException`. |

## For AI Agents

### Working In This Directory
- `DatabaseException` (main `exception/meeting/DatabaseException.kt`) is actually asserted only indirectly:
  `repository/meeting/MeetingRepositoryImplTest` expects it from `getMeeting` / `getParticipants` on a
  missing row.
- Either give this spec real cases (construction, message, cause propagation, and whichever `exception/`
  types have behaviour) or delete it. Leaving it as-is is the kind of stub the project's completion rules
  treat as a blocker.
- If you fill it in, keep the file in this package and the class under test's package aligned
  (`dev.notypie.exception` vs `dev.notypie.exception.meeting`) — rename or move the spec accordingly.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.exception.*'
```
Currently passes trivially with zero executed tests.

### Common Patterns
None yet; follow `common/` for the plain-unit-spec shape.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/exception/` — the classes this package is meant to cover

### External
Kotest only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
