<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-26 -->

# infrastructure/repository/authorization

## Purpose
Persistence for command-role grants: one `user_command_role` row per workspace member who has an explicit
`UserRole`; absence of a row means the default `USER`.

## Key Files
| File | Description |
|------|-------------|
| `UserCommandRoleRepository.kt` | Port: `findRole(userId): UserRole?`, `findAllGrants(): Map<String, UserRole>`, `saveRole(userId, role)`, `deleteRole(userId): Boolean` |
| `UserCommandRoleRepositoryImpl.kt` | `open class`; `@Transactional` on `saveRole` (insert or update-when-changed) and `deleteRole` (`deleteByUserId(...) > 0`) |
| `JpaUserCommandRoleRepository.kt` | Derived `findByUserId(userId): UserCommandRoleSchema?` and `deleteByUserId(userId): Long` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `UserCommandRoleSchema` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **The derived `deleteByUserId` needs an active transaction** — Spring Data runs derived deletes as a
  load-then-remove, which is why `deleteRole` is `@Transactional`. Do not call the Jpa method directly
  from a non-transactional caller.
- **`findAllGrants` loads every row** (`findAll().associate`); fine at workspace scale, but it backs the
  `list roles` command and the resolver, so do not put it on a per-message hot path.
- **`UserRole` is stored by name** (`@Enumerated(STRING)`, `length = 32`). Renaming a constant in
  `domain/command/authorization/UserRole` orphans existing grants. **Adding** a constant also needs a `V*`
  migration that alters `user_command_role.role`: `V12__add_user_command_role_table.sql` declares it as
  `ENUM ('USER', 'AI_USER', 'DEVELOPER', 'ADMIN')`, so H2 test schemas (regenerated each run) accept the new
  constant, a local or dev database whose table already exists keeps the old column under `ddl-auto: update`,
  and production (`ddl-auto: none`) fails the first `grant` of it with "Data truncated for column 'role'"
  (strict `sql_mode`, the MariaDB default).
- Rows are managed by the in-bot `grant` / `revoke` commands (`RoleManagementService`) and read by
  `CommandRoleResolver`; the same `UserRole` is also written to `mcp_tool_call_history.resolved_role`
  with `length = 16` — keep constant names within 16 characters.
- Migration: `V12__add_user_command_role_table.sql`. Bean: `JpaConfiguration.userCommandRoleRepository`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
`UserCommandRoleRepositoryImplTest` (`@DataJpaTest`) covers one path: `saveRole` insert, then `saveRole`
with another role updates the same row (one row, new role through `findRole`). It does not pin the
same-role no-op, `deleteRole`, `findAllGrants`, or the `ENUM` column of the production migration (H2 is
Hibernate-generated). `RoleManagementServiceTest` in `:application` mocks the port.

### Common Patterns
- Port / `open class *Impl` with `@Transactional` on writes / `Jpa*Repository` triple.
- Read-then-write upserts guarded by a unique constraint rather than native `INSERT ... ON DUPLICATE`.

## Dependencies

### Internal
- `domain/command/authorization/UserRole`
- `repository/authorization/schema`

### External
Spring Data JPA, Spring `@Transactional`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
