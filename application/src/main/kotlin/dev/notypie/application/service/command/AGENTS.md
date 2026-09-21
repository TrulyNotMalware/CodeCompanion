<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

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
| `CommandRoleResolver.kt` | `@Service class CommandRoleResolver(appConfig, userCommandRoleRepository)`. `bootstrapAdmins: Set<String>` from `slack.app.authorization.bootstrap-admins`; `resolve(userId)` = bootstrap → `ADMIN`, else `findRole(userId) ?: USER`; `isBootstrapAdmin(userId)` |
| `RoleManagementService.kt` | `@Service`. `@Transactional @EventListener handleRoleManage(RoleManageRequestEvent)` runs `GRANT` (`saveRole`), `REVOKE` (`deleteRole`, reports "no role grant" when nothing was removed) or `LIST` (`renderGrants()`), refuses to touch bootstrap admins, and stages a `ChannelMessage` headlined `CodeCompanion — role management` via `checkNotNull(outboundStager.stage(...))` + `publishOne`. `internal fun renderGrants()` is shared with the MCP `list_roles` tool |

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
  actions — `SlackMentionEventHandlerImpl` (sets `actorRole` on the command), `mcp/McpToolGate` — must
  call `resolve` per request; never cache a role across a turn.
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
the staged `ChannelMessage` text. Build `InboundCommand`s with the domain testFixtures creators and
`AppConfig(authorization = AppConfig.Authorization(bootstrapAdmins = listOf(...)))` for the resolver.

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
