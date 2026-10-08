<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-07 -->

# application/resources

## Purpose
Runtime configuration and deployment assets: the Spring profile YAML tree, the versioned SQL migration
set, the Kubernetes manifests the deploy workflow applies, and the Debezium/MariaDB CDC stack used to
stand up change-data-capture locally and in-cluster.

## Key Files
| File | Description |
|------|-------------|
| `application.yaml` | Base defaults only — kept deliberately minimal. MCP server off by default; `spring.jpa.open-in-view: false`; scheduler pool sized to 4 (effective through `SchedulingConfig`'s `ThreadPoolTaskScheduler`) |
| `application-local.yaml` | Local orbstack infra: MariaDB on 3306, 3-broker Kafka on 19092/29092/39092, virtual threads on, `ddl-auto: update`, `show-sql: true`; HTTP bound to `server.address: 127.0.0.1` (the only profile allowed a blank signing secret, with unauthenticated actuator endpoints); `slack.app.calendar.google.*` from `GOOGLE_CALENDAR_ENABLED` (false), `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT_URI`, `GOOGLE_TOKEN_ENCRYPTION_KEY` |
| `application-dev.yaml` | Development environment: MariaDB/Kafka from env vars, CDC + Kafka, port 9000, actuator `health,info,metrics,prometheus` with `show-details: when_authorized`, `SLACK_SIGNING_SECRET` required; the same `slack.app.calendar.google.*` `GOOGLE_*` placeholders as local (off by default) |
| `application-prod.yaml` | Production: env-var driven except the actuator base path (fixed `/actuator`, which the k8s probes and the deploy health check hard-code), actuator `health,info,metrics,prometheus` (scrape `/actuator/prometheus` in-cluster; never routed publicly), `ddl-auto: none`, `show-sql: false`, 10s graceful shutdown, H2 console off; the same `slack.app.calendar.google.*` `GOOGLE_*` placeholders as local (off by default) |
| `application-slack-live.yaml` | Live Slack workspace test: POLLING outbox relay + APPLICATION_EVENT publisher (no Kafka/Debezium), MariaDB URL/user defaults but `DATABASE_USER_PWD` required (no committed default), port 9000, `SLACK_SIGNING_SECRET` required (no default); the port is tunnelled to the internet, so the actuator exposes `health` only, with `show-details: when_authorized`. `configurations/ProfileYamlTest` pins both; `slack.app.calendar.google.*` `GOOGLE_*` placeholders (off by default) — the tunnel URL + `/oauth/google/callback` is the redirect URI to register in Google Cloud |
| `banner.txt` | Spring Boot startup banner |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `db/migration/` | `V1__` … `V22__` SQL migrations (outbox and its indexes/attempt and send counts, meeting, standup, agenda dispatch, agent session/turn history, user command roles, MCP tool call history, CVE tables and indexes, data fixes) — see `db/migration/AGENTS.md` |
| `k8s/` | `deployment.yaml`, `service.yaml`, `configmap.yaml`, `secret.yaml` + `route/` (`ingress.yaml`, `httpRoute.yaml`) — see `k8s/README.md` (see `k8s/AGENTS.md`) |
| `cdc/docker-compose/` | Local Debezium + MariaDB stack (`docker-compose.yml`, `debezium/connect_mariadb.sh`, `mariadb/my.cnf`) — see its `README.md` |
| `cdc/k8s/yamls/mariadb/` | In-cluster MariaDB StatefulSet, service, config, and init job |
| `cdc/` | CDC relay infrastructure: local Docker Compose stack (MariaDB with binlog, KRaft Kafka, Debezium Connect) and cluster MariaDB manifests (see `cdc/AGENTS.md`) |
| `db/` | Container for the versioned SQL patch set (see `db/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **The base `application.yaml` is intentionally thin.** Only cross-profile *safety* defaults belong
  there (e.g. MCP server disabled so the starter does not auto-expose an open `/mcp`). Environment
  detail belongs in the profile file.
- **Migrations are incremental patches, not a full schema.** Local runs rely on
  `ddl-auto: update` to create base tables, then the `V*` scripts patch them. Prod runs `ddl-auto: none`.
  So: a new migration must be additive and safe against a Hibernate-created base table, and any new
  entity needs *both* a JPA schema class and a migration.
- **Never renumber or edit an applied migration.** The highest number in the working tree on 2026-10-02 is
  `V23__`, so the next free one is `V24__`. Check the highest existing `V*` here and on `origin/main` before
  naming a new one.
- **Prod config is env-var only** (`${SQL_DATABASE_URL}`, `${MCP_ENABLED}`, ...). Do not commit a literal
  secret or host here; add the variable to `k8s/configmap.yaml` / `k8s/secret.yaml` instead.
- **`k8s/deployment.yaml` is templated with `envsubst '${IMAGE_NAME}'`** by `.github/workflows/deploy_action.yaml`.
  Keep the placeholder syntax intact or the deploy breaks.
- **Kafka consumer sizing** (`local`, `dev`, `prod`): `max-poll-records: 5` and `max.poll.interval.ms: 300000`,
  i.e. 300s for a batch of 5, 60s per record on average. What one PENDING-row record costs is budgeted in one
  place: the Slack HTTP bound (render's profile lookup plus the dispatch retries) only in
  `infrastructure/src/main/kotlin/dev/notypie/impl/command/AGENTS.md`, and the claim, renew and completion SQL in
  `kotlin/dev/notypie/application/service/relay/AGENTS.md` ("Per-record time budget"). Do not copy those totals
  here; redo them there whenever these two values change, and before adding any wait inside the listener. If a batch does overrun, the consumer leaves the group and the
  records are redelivered; an already-claimed row is no longer PENDING, so the CDC processor skips it.
- **Hikari pool** (`maximum-pool-size: 20` in every profile). A meeting cancel/reschedule/add-participant takes
  one connection at a time: the handler defers the `REQUIRES_NEW` write from `isolatedWriteTemplate` until the
  interaction transaction has committed and released its connection (`service/meeting/AGENTS.md`; only an inline
  caller outside the handler still holds two). An `@bot` mention that a full `agentTurnExecutor` rejects does hold
  two: its overload notice is a `REQUIRES_NEW` write inside `afterCompletion`, while the committed mention
  transaction still holds its connection (measured on H2: two sessions there, one in a plain transaction). The relay executor, the schedulers and
  the CDC listener share the same pool. Size it as: concurrent meeting interactions + mentions rejected at the same
  moment x 2 + relay workers (`relayTaskExecutor`, 4) + scheduler threads (`spring.task.scheduling.pool.size`, 4) + CDC listener threads
  (1) + AI turns that use the DB (`agentTurnExecutor`, `slack.app.agent.turns.max-concurrent`, 4). Request threads are virtual,
  so the pool, not a thread limit, is what bounds concurrent interactions; a request that cannot get a connection
  fails after `connection-timeout`. MariaDB must allow pool size x Pods: 2 replicas x 20 = 40 (the ceiling while
  `k8s/deployment.yaml` keeps `strategy: Recreate`), 60 once that block is removed and a rolling update runs a
  surge Pod, plus Debezium's connection and any operator sessions, within `max_connections`.
- Every new `slack.app.*` property needs a matching default in
  `application/configurations/AppConfig.kt`, or binding silently falls back.

### Testing Requirements
- `infrastructure/src/test/resources/application.yaml` is the test profile (H2 + `EmbeddedKafka`);
  changes to entity mappings must keep it booting.
- After adding a migration, verify it applies against a `slack-live`-profile database — H2/`ddl-auto` tests
  will *not* catch a MariaDB-specific syntax error.
- Deployment manifest changes are verified by the deploy workflow's rollout + in-cluster
  `/actuator/health/readiness` check (API server service proxy, falling back to `kubectl exec`); there is no local
  test for them.

### Common Patterns
- One file per profile, activated by `spring.config.activate.on-profile`.
- Virtual threads (`spring.threads.virtual.enabled: true`) are on in every profile.
- Migration naming: `V<n>__<snake_case_description>.sql`.
- YAML comments explain *why* a setting is what it is — keep that habit; several of these settings
  (scheduler pool size, MCP default) exist to prevent a specific failure.

## Dependencies

### Internal
- `application/configurations/AppConfig.kt` — binds the `slack.app.*` tree in these files
- `infrastructure/repository/*/schema/` — JPA schemas the migrations must stay in sync with

### External
MariaDB (prod/local) and H2 (test), Apache Kafka, Debezium connector, Kubernetes (OKE) + Harbor registry.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
