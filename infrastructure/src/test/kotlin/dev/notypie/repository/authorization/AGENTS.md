<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/authorization

## Purpose
H2 spec for the role-grant lane in main `repository/authorization/`.

## Key Files
| File | Description |
|------|-------------|
| `UserCommandRoleRepositoryImplTest.kt` | `@DataJpaTest` on `UserCommandRoleRepositoryImpl`: granting `DEVELOPER` and then `ADMIN` to the same user updates the one grant row through `UserCommandRoleSchema.changeRole` |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.authorization.*'
```
The case runs inside the `then` leaf, so `@DataJpaTest` rolls it back and the shared H2 keeps no rows.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
