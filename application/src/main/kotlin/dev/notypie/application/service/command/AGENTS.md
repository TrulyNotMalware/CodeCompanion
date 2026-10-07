<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-07 -->

# application/service/command

## Purpose
The command pipeline hub and role authority. `CommandExecutor` runs a domain `Command`, drains the
effects it accumulated, resolves them into transport events, and publishes them; every inbound entry
point (slash services, mention handler, interaction handler) ends here. `CommandRoleResolver` is the
single source of truth for a user's `UserRole`, and `RoleManagementService` applies the admin-only
`@bot grant | revoke | roles` mentions.

## Key Files
| File | Description |
|------|-------------|
| `CommandExecutor.kt` | `class CommandExecutor(intentResolver: SlackIntentResolver, outboundStager, eventPublisher)`. `execute(command): CommandOutput` = `command.handleEvent()` → `command.drainIntents()` (always, so error effects reach Slack) → `publishIntents`: classifies every effect explicitly (`CommandIntent` / `OutboundMessage`; an unrouted third `CommandEffect` implementor fails loudly instead of being dropped — the interface cannot be sealed across packages), builds `basicInfo = commandData.extractBasicInfo(idempotencyKey)`, `intentResolver.resolveAll(...) + outbound.mapNotNull { outboundStager.stage(...) }`, queues into `DefaultEventQueue`, `eventPublisher.publishEvent(events)`. Resolution and publish failures are logged with `commandId` / `idempotencyKey` / counts and rethrown. Declared as `@Bean commandExecutor` in `configurations/SlackRequestBuilderConfiguration` with the `SlackOutboundStager` |
| `CommandRoleResolver.kt` | `@Service class CommandRoleResolver(appConfig, userCommandRoleRepository, clock, meterRegistry)` plus `data class RoleResolution(role, lookupFailed)`. `bootstrapAdmins: Set<String>` from `slack.app.authorization.bootstrap-admins`; `resolve(userId)` = bootstrap → `ADMIN`, else a per-JVM cache of `USER` answers only (no row; `CACHE_TTL` 60 s, at most 10 000 users; when full, expired entries are dropped and new ones are not cached) in front of `findRole(userId) ?: USER`, so `AI_USER` / `DEVELOPER` / `ADMIN` are read from the DB on every call; a lookup exception is logged at `WARN`, counted in `codecompanion.role.lookup.failures` (Prometheus `codecompanion_role_lookup_failures_total`, per replica) and answered with `USER` (not cached). `resolution(userId)` returns the same role with `lookupFailed = true` on that fallback so a caller can tell an outage from a real `USER`; `resolve(userId)` is `resolution(userId).role`. `evict(userId)` bumps an eviction generation and drops the entry; `resolve` stores its result only if no eviction happened since its lookup began (checked inside `ConcurrentHashMap.compute`), so a lookup that read the old role before an eviction cannot re-cache it; `isBootstrapAdmin(userId)` |
| `RoleManagementService.kt` | `@Service`. `@Transactional @EventListener handleRoleManage(RoleManageRequestEvent)` runs `GRANT` (`saveRole`), `REVOKE` (`deleteRole`, reports "no role grant" when nothing was removed) or `LIST` (`renderGrants()`); after a `GRANT` / `REVOKE` write it evicts the target from `CommandRoleResolver` in `TransactionSynchronization.afterCommit` (immediately when no transaction synchronization is active); refuses to touch bootstrap admins, and stages an `Ephemeral` to the requester (`recipient = UserRef(responseBasicInfo.publisherId)`) headlined `CodeCompanion — role management` via `checkNotNull(outboundStager.stage(...))` + `publishOne`. `internal fun renderGrants()` is shared with the MCP `list_roles` tool |

## For AI Agents

### Working In This Directory
- `CommandExecutor` never re-queues intents after a failed publish. Publishers dispatch sequentially,
  so a retry could double-publish; retries belong upstream (outbox relay, Kafka, Slack replay) keyed by
  the shared `idempotencyKey`. The rethrow is what rolls back the caller's `@Transactional`.
- `outboundStager.stage` returning null is silently dropped here (`mapNotNull`) but treated as fatal
  (`checkNotNull`) by the listeners that must reply. Know which contract you are in before "fixing"
  either side.
- Role resolution order is config → `user_command_role` row → `USER`, and bootstrap admins are
  immutable from chat (`grant` / `revoke` return an explanatory message instead). Callers that gate
  actions — `SlackMentionEventHandlerImpl` / `SlackInteractionHandlerImpl` (set `actorRole` on the
  command), `mcp/McpToolGate` — must call `resolve` per request; never keep a role of their own across a
  turn. The resolver's cache is the only role cache.
- Role cache staleness: a grant/revoke is visible on the replica that committed it as soon as the commit
  finishes — eviction is registered from inside the writing transaction and runs `afterCommit`, and the
  eviction generation stops a lookup that read the pre-commit row from caching it (that one in-flight call
  may still answer with the old role). Only `USER` is cached, so a revoke reaches every replica on the next call
  (a cached elevated role let a revoked admin re-grant themselves on another replica within the TTL); a grant to
  a user another replica still caches as `USER` waits up to `CACHE_TTL` (60 s) there, which can only deny.
  The generation is global, not per user: an eviction only costs concurrent lookups one cache store.
  `RoleManagementService` is the only writer of `user_command_role`; another writer must call
  `CommandRoleResolver.evict` after its commit the same way. Do not evict from an event listener: with
  `fallbackExecution` it ran before the write when no transaction was active.
- **A failed lookup degrades to `USER`** (logged at `WARN`, not cached), so a role-table hiccup denies elevated
  commands instead of failing every interaction and mention. That is only safe because both handlers resolve
  **before** they open their transaction: inside one, the repository's `readOnly` transaction joins it and the
  failed query marks it rollback-only, so the fallback would still end in `UnexpectedRollbackException` (500) at
  commit (`RoleLookupJpaTransactionTest` shows this on a real `JpaTransactionManager`). A new caller must resolve
  outside its transaction too. `McpToolGate` therefore sees `USER` and denies elevated tools. Cache hits never
  touch the DB.
- `handleRoleManage` is `@Transactional` because the staged reply is only persisted by the
  `BEFORE_COMMIT` listener in `service/relay`; the role write and the confirmation must share one
  transaction even though the event arrives via `EventPublisher` from `CommandExecutor`.
- `RoleManageRequestEvent` is produced by `SlackIntentResolver` only after the domain parser has
  already verified the actor is `ADMIN`; do not add a second permission check here, add it in the
  parser so the denial message is consistent.
- `renderGrants()` output is user-facing Slack mrkdwn (`• <@id> — \`role\``); the MCP tool relays it
  verbatim, so keep it plain text.

### Testing Requirements
```bash
./gradlew :application:test --tests '*CommandExecutorTest*' --tests '*CommandRoleResolverTest*' --tests '*RoleManagementServiceTest*'
```
All three are Kotest `BehaviorSpec` + MockK. `CommandExecutorTest` uses a MockK `Command` whose
`drainIntents()` returns mixed `CommandIntent` / `OutboundMessage` lists and asserts the resolver and
stager calls, the published queue size, and that a resolver / publisher exception propagates.
`RoleManagementServiceTest` asserts the repository call per action, the bootstrap-immutable branch, and
the staged `Ephemeral` text. Build `InboundCommand`s with the domain testFixtures creators and
`AppConfig(authorization = AppConfig.Authorization(bootstrapAdmins = listOf(...)))` for the resolver; pass a
`clock` to drive cache expiry.

### Common Patterns
- Generic `execute<T : SubCommandDefinition>(command: Command<T>)` so the executor is agnostic of the
  concrete slash / interaction command.
- `runCatching`-free explicit `try / catch (e: Exception)` with structured `log.error` then `throw e`.
- Listener services: `@Transactional @EventListener`, a `when (payload.action)` returning the reply
  text, then `checkNotNull(stage(...))` + `publishOne`.

## Dependencies

### Internal
- `domain/command/` — `Command`, `SubCommandDefinition`, `CommandOutput`, `CommandEffect`,
  `CommandIntent`, `DefaultEventQueue`, `EventPublisher`, `RoleManageRequestEvent` / `RoleManagePayload` / `RoleManageAction`
- `domain/command/authorization/UserRole`, `domain/command/outbound/`
- `infrastructure/impl/command/SlackIntentResolver`, `SlackOutboundStager`
- `infrastructure/repository/authorization/UserCommandRoleRepository`
- `application/configurations/AppConfig.Authorization`, `SlackRequestBuilderConfiguration`
- Callers: `service/meeting`, `service/standup`, `service/cve/{subscription,query}`, `service/mention`,
  `service/interaction`, `mcp/`

### External
Spring `@Service` / `@Transactional` / `@EventListener`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
