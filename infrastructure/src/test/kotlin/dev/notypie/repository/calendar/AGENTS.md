<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/calendar

## Purpose
`@DataJpaTest` specs (embedded H2) for the Google Calendar connection store and the OAuth state ledger.

## Key Files
| File | Description |
|------|-------------|
| `JpaGoogleOAuthStateRepositoryTest.kt` | consume returns the user once and `null` on replay; an expired state returns `null` and stays unconsumed; an unknown state returns `null`; `deleteForUser` removes that user's two live states and leaves another user's; `deleteExpired` removes only rows past the cutoff |
| `GoogleCalendarConnectionRepositoryImplTest.kt` | two `saveActive` calls keep one row and rewrite subject, e-mail, token and `connected_at`; the second save reports the replaced row (its token, subject and e-mail) and no token appears in `ConnectionSaved.toString`; `delete` returns `true` then `false`; `find` of a stranger is `null` |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.calendar.*'
```
Adapters are built by hand over the autowired `Jpa*` repositories (`JpaConfiguration` is not loaded in a
`@DataJpaTest`). Flush before asserting a derived delete; the bulk JPQL purge needs no flush.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
