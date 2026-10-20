<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-20 -->

# domain/command/dto

## Purpose
The per-request envelope (`CommandBasicInfo`) that every context, output and staged event carries, plus
two sub-packages: the Block Kit-neutral modal content DTOs (`modals/`) and the command result type
(`response/`).

## Key Files
| File | Description |
|------|-------------|
| `CommandBasicInfo.kt` | `CommandBasicInfo(appId, publisherId, channel, idempotencyKey)`; `forOutbound(publisherId, channel, appId = "", idempotencyKey = random)` for bot-initiated sends. It is encoded into every outbox row's `basicInfo`, so a field added or removed here is an outbox format change (`appToken` was removed on 2026-10-20 with schema V7, `docs/wiki/decisions.md` #50) |
| `UrlVerificationRequest.kt` | `internal data class` (`type`, `channel`, `token`, `challenge`) — unreferenced anywhere in the repo |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `modals/` | `ApprovalContents`, `MultiUserSelectContents`, `TextInputContents`, `TimeScheduleInfo` (see `modals/AGENTS.md`) |
| `response/` | `CommandOutput` (`empty` / `fail` / `success`) and `Status` (see `response/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Inbound path: `InboundCommand.extractBasicInfo(idempotencyKey)` builds it with `publisherId = actorId`.
  Outbound path: `forOutbound(...)` (15 call sites — scheduler DMs, channel summaries, fallback
  ephemerals) with a fresh random key unless one is supplied.
- There is no per-request token: outbound calls authenticate with the bot token configured on the API client, and
  the Slack verification token that inbound bodies still carry is dropped at the infrastructure DTOs (#50).
- `idempotencyKey` is the one key the whole effect chain shares (command → intents → events → outbox
  rows).
- `UrlVerificationRequest` is dead: the Slack URL-verification handshake does not pass through the
  domain. Remove it or leave it, but do not build on it.

### Testing Requirements
No spec targets this package directly; every context spec constructs a `CommandBasicInfo` through the
`InboundCommandCreator` fixture. The layering guard covers it:
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.architecture.DomainLayeringGuardTest'
```

### Common Patterns
- `data class` with a `companion object` factory for the alternative construction path.

## Dependencies

### Internal
None.

### External
`java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
