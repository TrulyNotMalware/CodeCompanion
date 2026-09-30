<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-30 -->

# test/kotlin/dev/notypie/application/service/command

## Purpose
Specs for the command pipeline hub and role gating: `CommandExecutor` (drain intents → resolve → publish, with
re-throw semantics), `CommandRoleResolver` (bootstrap admins → DB grant → `USER`), `RoleManagementService`
(`grant`/`revoke`/`list` replies), and where the inbound handlers resolve the role relative to their transaction.

## Key Files
| File | Description |
|------|-------------|
| `CommandExecutorTest.kt` | `CommandExecutor.execute(command)` with MockK `SlackIntentResolver`, `OutboundMessageStager`, `EventPublisher` and `isolationMode = InstancePerLeaf`. Commands come from `domain` `TestCommand(idempotencyKey, commandData, intentToProduce)`. One intent, both ports succeed → `output.ok`, `drainIntents()` empty, resolver and publisher each called once; resolver throws → re-thrown, publisher never called; publisher throws → re-thrown and intents stay drained (no re-queue); `intentToProduce = null` → resolver never called; resolver returns an empty list → publisher not called. |
| `CommandRoleResolverTest.kt` | `CommandRoleResolver.resolve(userId)` with `AppConfig.Authorization(bootstrapAdmins)` and a MockK `UserCommandRoleRepository`: bootstrap admin → `ADMIN` with no DB call; DB grant → that role; unknown → `USER`. Cache (only `USER` is cached): a user without a grant resolved twice within the TTL → one `findRole` call; a `DEVELOPER` resolved twice → two calls; `ADMIN` then a row deleted by "another replica" (no eviction here) → the very next resolve answers `USER` (T10); a cached `USER` granted elsewhere → still `USER` within the TTL, the new role after `CACHE_TTL` (file-private `MutableClock`); `evict(userId)` after a local grant → the next lookup reads `ADMIN`; an eviction fired from inside `findRole` while it reads the old `USER` → that call answers `USER` but does not cache it, the next resolve reads `ADMIN`; a throwing repository → `USER`, and the failure is not cached. |
| `RoleLookupJpaTransactionTest.kt` | Real Hibernate `EntityManagerFactory` on H2 for `repository/authorization/schema` (file-private `RoleStore` / `roleStore(createSchema)`: `JpaRepositoryFactory` + `PersistenceExceptionTranslationInterceptor` → `UserCommandRoleRepositoryImpl` → a `spyk` of the real `CommandRoleResolver` that records its answers) and a `JpaTransactionManager`. Without DDL the `user_command_role` table is missing: `resolve` inside a `TransactionTemplate` answers `USER` but the commit throws `UnexpectedRollbackException` (the T11 mechanism); `SlackInteractionHandlerImpl.handleInteraction` and `SlackMentionEventHandlerImpl.handleEvent(commandData)` degrade to `USER` and their transaction completes `STATUS_COMMITTED`. With the table and an `ADMIN` row both handlers resolve `ADMIN` and commit. |
| `RoleManagementServiceTest.kt` | `RoleManagementService.handleRoleManage(event)` over a real `CommandRoleResolver` via `createRoleManageRequestEvent(action, targetUserId, role)`. `GRANT` → `saveRole` and "Granted `ai_user` to <@U>."; `REVOKE` existing → "Revoked the role grant of <@U>. They fall back to `user`."; `REVOKE` missing → "<@U> has no role grant."; `LIST` → "• <@U> — `role`" lines or "No role grants. Everyone defaults to `user`."; bootstrap-admin target → nothing written, config-ownership reply; `LIST` with a bootstrap admin configured → "(bootstrap, config-managed)" marker beside DB grants. Cache eviction: `GRANT` to a cached `USER` under `TransactionSynchronizationManager.initSynchronization()` → the resolver still serves `USER` until the registered `afterCommit` runs, then `ADMIN`; `REVOKE` of an `ADMIN` → the next resolve reads the revoked row even before any eviction (nothing elevated is cached); `GRANT` without synchronization → evicted right after the write. `serviceWith(..., commandRoleResolver)` lets a case share the resolver. |

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
`RoleLookupJpaTransactionTest` boots two small `EntityManagerFactory`s (about a second each); the others are
pure MockK.
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
