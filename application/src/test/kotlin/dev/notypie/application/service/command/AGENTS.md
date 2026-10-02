<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# test/kotlin/dev/notypie/application/service/command

## Purpose
Specs for the command pipeline hub and role gating: `CommandExecutor` (drain intents → resolve → publish, with
re-throw semantics), `CommandRoleResolver` (bootstrap admins → DB grant → `USER`), and `RoleManagementService`
(`grant`/`revoke`/`list` replies).

## Key Files
| File | Description |
|------|-------------|
| `CommandExecutorTest.kt` | `CommandExecutor.execute(command)` with MockK `SlackIntentResolver`, `OutboundMessageStager`, `EventPublisher` and `isolationMode = InstancePerLeaf`. Commands come from `domain` `TestCommand(idempotencyKey, commandData, intentToProduce)`. One intent, both ports succeed → `output.ok`, `drainIntents()` empty, resolver and publisher each called once; resolver throws → re-thrown, publisher never called; publisher throws → re-thrown and intents stay drained (no re-queue); `intentToProduce = null` → resolver never called; resolver returns an empty list → publisher not called. |
| `CommandRoleResolverTest.kt` | `CommandRoleResolver.resolve(userId)` with `AppConfig.Authorization(bootstrapAdmins)` and a MockK `UserCommandRoleRepository`: bootstrap admin → `ADMIN` with no DB call; DB grant → that role; unknown → `USER`; a user without a grant resolved twice within the TTL → one `findRole` call; an elevated grant resolved twice → two calls (only `USER` is cached); an `ADMIN` revoked on another replica (no eviction here) → the very next call answers `USER`; a cached `USER` granted elsewhere → still `USER` within the TTL, the new role after `CACHE_TTL` (file-private `MutableClock`); `evict(userId)` after a grant → the next lookup reads the new role; an eviction fired from inside `findRole` (a lookup overtaken by an eviction) → that call answers the old `USER` but does not cache it, the next resolve reads the DB; a throwing repository → `USER`, not cached, so the next resolve reads the stored role. |
| `RoleLookupJpaTransactionTest.kt` | A real Hibernate + `JpaTransactionManager` stack on H2 (file-private `RoleStore`: `LocalContainerEntityManagerFactoryBean` scanning `repository/authorization/schema`, `JpaRepositoryFactory` with `PersistenceExceptionTranslationInterceptor`, the real `CommandRoleResolver` spied only to record its answers). Without schema generation every role query fails in SQL: resolving inside a `TransactionTemplate` ends in `UnexpectedRollbackException` (the fallback cannot save a joined transaction), while `SlackInteractionHandlerImpl.handleInteraction` and `SlackMentionEventHandlerImpl.handleEvent` both answer `USER` and commit. With an `ADMIN` row both resolve `ADMIN` and commit. Moving either lookup back inside its transaction fails it. |
| `RoleManagementServiceTest.kt` | `RoleManagementService.handleRoleManage(event)` over a real `CommandRoleResolver` via `createRoleManageRequestEvent(action, targetUserId, role)`. `GRANT` → `saveRole` and "Granted `ai_user` to <@U>."; `REVOKE` existing → "Revoked the role grant of <@U>. They fall back to `user`."; `REVOKE` missing → "<@U> has no role grant."; `LIST` → "• <@U> — `role`" lines or "No role grants. Everyone defaults to `user`."; bootstrap-admin target → nothing written, config-ownership reply; `LIST` with a bootstrap admin configured → "(bootstrap, config-managed)" marker beside DB grants. Cache eviction: a `GRANT` to a cached `USER` under `TransactionSynchronizationManager.initSynchronization()` → the resolver still serves `USER` until the registered `afterCommit` runs, then the new role; a `REVOKE` of an `ADMIN` under synchronization → the next resolve already reads `USER` before any eviction (nothing elevated was cached); `GRANT` without synchronization → evicted right after the write. `serviceWith(..., commandRoleResolver)` lets a case share the resolver. |

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
