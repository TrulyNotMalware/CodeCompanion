<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-22 -->

# infrastructure/src/test/kotlin/dev/notypie/exception

## Purpose
Mirror of main `exception/`: the spec for the `DatabaseException` helpers in `exception/meeting/DatabaseException.kt`.

## Key Files
| File | Description |
|------|-------------|
| `DatabaseExceptionTest.kt` | `throwIfSchemaNotFound` on a null receiver → `DatabaseException` with `tableName` = the receiver's static type, `errorCode = JpaErrorCode.TABLE_NOT_FOUND`, message from the code, one `ExceptionArgument` (`fieldName`, stringified `value`, default reason); on a non-null receiver → returned unchanged. `schemaNotFound { table(...); field(...) withValue ...; reason(...) }` → every builder call lands on the exception |

## For AI Agents

### Working In This Directory
- `MeetingRepositoryImplTest` still covers the repository-side throw (`getMeeting` / `getParticipants` on a
  missing row); this spec covers the helper contract itself. Until 2026-09-22 it was an empty placeholder.
- `CodeCompanionRuntimeException.errorCode` is a property now; assert on it rather than on the message string
  when the code is what matters.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests '*DatabaseExceptionTest*'
```
