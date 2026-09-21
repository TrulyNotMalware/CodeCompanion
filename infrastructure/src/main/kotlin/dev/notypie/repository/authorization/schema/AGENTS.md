<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/repository/authorization/schema

## Purpose
The single JPA entity behind the role system.

## Key Files
| File | Description |
|------|-------------|
| `UserCommandRoleSchema.kt` | `@Entity(name = "user_command_role")`, `uk_user_command_role_user_id` on `user_id` (64). `id` IDENTITY, `user_id`, `role: UserRole` `@Enumerated(STRING)` (32, `var`), `created_at` (`@CreationTimestamp`), `updated_at` (`@UpdateTimestamp`) |

## For AI Agents

### Working In This Directory
- **`role` is the only mutable column**; `UserCommandRoleRepositoryImpl.saveRole` flips it in place
  rather than deleting and re-inserting, so `created_at` records the first grant.
- **This entity references a domain enum directly** (`dev.notypie.domain.command.authorization.UserRole`);
  the DB stores the constant name, so the enum is effectively part of the schema contract.
- `user_id` is the raw Slack user id (`U…`), 64 chars, no FK — the app has no user table.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
No schema-level spec exists (see `../AGENTS.md`).

### Common Patterns
- `@field:` Jakarta annotations, IDENTITY id, `@Enumerated(EnumType.STRING)` with explicit `length`.

## Dependencies

### Internal
- `domain/command/authorization/UserRole`

### External
Jakarta Persistence, Hibernate timestamp annotations.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
