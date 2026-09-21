<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/command/authorization (test)

## Purpose
Pins the role ladder `USER ⊂ AI_USER ⊂ DEVELOPER ⊂ ADMIN` that `AppMentionContextParser` consults
before routing a command. The denial messages themselves are asserted in `../parsers/`.

## Key Files
| File | Description |
|------|-------------|
| `UserRoleTest.kt` | `UserRole.grants(permission)`: `USER` holds `BASIC` only; `AI_USER` adds `AI` but not `OPERATIONS`; `DEVELOPER` adds `OPERATIONS` but not `ADMINISTRATION`; `ADMIN` grants every entry of `CommandPermission.entries`, so a permission added later is admin-only by default. `BehaviorSpec`, no fixtures |

## For AI Agents

### Working In This Directory
- When a `CommandPermission` value is added, extend the three non-admin `then` blocks with an explicit
  `false` (or `true`) for it. The `ADMIN` case is already exhaustive via `entries`.
- Keep this spec free of parser concerns — routing and denial text live in
  `../parsers/AppMentionContextParserTest.kt`.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.authorization.UserRoleTest'
```

### Common Patterns
- One `then` per role, each enumerating every permission it holds and at least one it lacks.

## Dependencies

### Internal
- `dev.notypie.domain.command.authorization.UserRole`, `CommandPermission`.

### External
- Kotest (`BehaviorSpec`, `shouldBe`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
