<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test/kotlin/dev/notypie/application/service/command

## Purpose
Specs for the command pipeline hub and role gating: `CommandExecutor` (drain intents → resolve → publish, with
re-throw semantics), `CommandRoleResolver` (bootstrap admins → DB grant → `USER`), and `RoleManagementService`
(`grant`/`revoke`/`list` replies).

## Key Files
| File | Description |
|------|-------------|
| `CommandExecutorTest.kt` | `CommandExecutor.execute(command)` with MockK `SlackIntentResolver`, `OutboundMessageStager`, `EventPublisher` and `isolationMode = InstancePerLeaf`. Commands come from `domain` `TestCommand(idempotencyKey, commandData, intentToProduce)`. One intent, both ports succeed → `output.ok`, `drainIntents()` empty, resolver and publisher each called once; resolver throws → re-thrown, publisher never called; publisher throws → re-thrown and intents stay drained (no re-queue); `intentToProduce = null` → resolver never called; resolver returns an empty list → publisher not called. |
| `CommandRoleResolverTest.kt` | `CommandRoleResolver.resolve(userId)` with `AppConfig.Authorization(bootstrapAdmins)` and a MockK `UserCommandRoleRepository`: bootstrap admin → `ADMIN` with no DB call; DB grant → that role; unknown → `USER`. |
| `RoleManagementServiceTest.kt` | `RoleManagementService.handleRoleManage(event)` over a real `CommandRoleResolver` via `createRoleManageRequestEvent(action, targetUserId, role)`. `GRANT` → `saveRole` and "Granted `ai_user` to <@U>."; `REVOKE` existing → "Revoked the role grant of <@U>. They fall back to `user`."; `REVOKE` missing → "<@U> has no role grant."; `LIST` → "• <@U> — `role`" lines or "No role grants. Everyone defaults to `user`."; bootstrap-admin target → nothing written, config-ownership reply; `LIST` with a bootstrap admin configured → "(bootstrap, config-managed)" marker beside DB grants. |

## For AI Agents

### Working In This Directory
- `CommandExecutorTest` is the only spec in the tree using `IsolationMode.InstancePerLeaf`; spec-scoped mocks
  are rebuilt per leaf so `every` stubs never leak. Other specs here share mocks across `when`s.
- The "publisher throws → intents remain drained" case is the documented no-re-queue contract; do not add a
  retry inside `CommandExecutor` without changing this case first.
- `RoleManagementServiceTest` asserts exact reply strings and its `markdown()` extension also asserts the
  target is `TEST_CHANNEL_ID`. Wording changes must update the spec in the same commit.
- Each `when` shares one `slot<OutboundMessage>()`; the last staged message is what `then` sees.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.command.*'
```
Fixtures used: `domain` testFixtures `command/TestCommandFactory.kt` (`TestCommand`),
`command/InboundCommandCreator.kt` (`createMentionInboundCommand`), `command/CommandDomainInputCreator.kt`
(`createRoleManageRequestEvent`), `Constants.kt` (`TEST_CHANNEL_ID`); `infrastructure` testFixtures
`impl/command/event/SlackEventTestFixtures.kt` (`createSendSlackMessageEvent`).

### Common Patterns
- `serviceWith(roleRepository, stagedMessage, bootstrapAdmins)` returns `Pair<Service, EventPublisher>` so a
  case can verify the publish count without owning the mock.
- `verify(exactly = 0)` on the repository write is the assertion for "bootstrap admin is immutable from chat".

## Dependencies

### Internal
- `application/service/command/CommandExecutor.kt`, `CommandRoleResolver.kt`, `RoleManagementService.kt`
- `application/configurations/AppConfig.Authorization`
- `domain/command/*` (`CommandIntent`, `OutboundMessage`, `UserRole`, `RoleManageAction`)
- `infrastructure/impl/command/SlackIntentResolver`, `infrastructure/repository/authorization/UserCommandRoleRepository`

### External
MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
