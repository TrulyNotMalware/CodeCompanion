<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/authorization

## Purpose
The role model that gates bot mentions: four capability groups (`CommandPermission`) and four roles
(`UserRole`) defined as nested permission sets. Pure enums — the role lookup itself lives in the
application layer.

## Key Files
| File | Description |
|------|-------------|
| `CommandPermission.kt` | `BASIC` (help, forms, modals, slash commands), `AI` (`ask` and the free-text fallback), `OPERATIONS` (`status`, `notice`), `ADMINISTRATION` (`grant`, `revoke`, `roles`, `cve ...`) |
| `UserRole.kt` | `USER` ⊂ `AI_USER` ⊂ `DEVELOPER` ⊂ `ADMIN`; `ADMIN` is `CommandPermission.entries.toSet()`; `grants(permission)` is the only query |

## For AI Agents

### Working In This Directory
- The gate is applied once, in `entity/parsers/AppMentionContextParser.parseContext`: the first mention
  token becomes a `CommandSet`, and `actorRole.grants(commandSet.requiredPermission)` decides between
  dispatch and a "You don't have permission" `TextResponseContext`. Slash commands and button/modal
  interactions are not role-gated in the domain — `InteractionContextParser` takes no role.
- `actorRole` is resolved by `application/service/command/CommandRoleResolver.kt`: bootstrap admin →
  `ADMIN`, else the `user_command_role` row (`infrastructure/repository/authorization/`, stored as
  `EnumType.STRING`), else `USER`. A role name is therefore both the mention vocabulary
  (`grant @user developer` parses with `UserRole.valueOf(token.uppercase())`) and a persisted value;
  renaming a constant is a data migration plus a `HELP_MESSAGE` change.
- `ADMIN` picks up any new `CommandPermission` automatically; every other role must list it
  explicitly. When you add a permission, decide which of `AI_USER` / `DEVELOPER` should hold it.
- The mapping from command to permission is `entity/CommandSet.requiredPermission`, not here. Free text
  (`CommandSet.UNKNOWN`) requires `AI` because it falls through to the assistant.
- `UserRole` also gates MCP tools (`application/mcp/McpToolGate.kt`) and rides on
  `CommandIntent.GrantRole` / `event/RoleManagePayload`.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.authorization.UserRoleTest'
```
`UserRoleTest` pins the hierarchy; `CommandSetTest` (`../`) pins each command's permission and
`AppMentionContextParserTest` (`../entity/parsers/`) pins the denial message. Add a case to all three
when a role or permission changes.

### Common Patterns
- Roles as explicit `setOf(...)` literals rather than ordinal comparison, so the hierarchy is readable
  and a non-linear role could be added without touching `grants`.

## Dependencies

### Internal
None.

### External
None beyond the Kotlin stdlib.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
