<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-01 -->

# application/resources

## Purpose
Runtime configuration and deployment assets: the Spring profile YAML tree, the versioned SQL migration
set, the Kubernetes manifests the deploy workflow applies, and the Debezium/MariaDB CDC stack used to
stand up change-data-capture locally and in-cluster.

## Key Files
| File | Description |
|------|-------------|
| `application.yaml` | Base defaults only — kept deliberately minimal. `spring.lifecycle.timeout-per-shutdown-phase: 60s` for every profile (one CDC record in flight must finish before the DataSource closes; `k8s/deployment.yaml`'s grace adds it to the relay executor's destroy-time wait); `spring.jpa.open-in-view: false` (see "Open-in-view" below); MCP server off by default; scheduler pool sized to 4 (read by the `taskScheduler` bean in `configurations/SchedulingConfig.kt`); `server.forward-headers-strategy: none` |
| `application-local.yaml` | Local orbstack infra: MariaDB on 3306, 3-broker Kafka on 19092/29092/39092, virtual threads on, `ddl-auto: update`, `show-sql: true`; HTTP bound to `server.address: 127.0.0.1` (the only profile allowed a blank signing secret, with unauthenticated actuator endpoints) |
| `application-dev.yaml` | Development environment: MariaDB/Kafka from env vars, CDC + Kafka, port 9000, actuator `health,info,metrics` with `show-details: when_authorized`, `SLACK_SIGNING_SECRET` required |
| `application-prod.yaml` | Production: env-var driven except the actuator base path (fixed `/actuator`, which the k8s probes and the deploy health check hard-code), `ddl-auto: none`, `show-sql: false`, 10s graceful shutdown, H2 console off |
| `application-slack-live.yaml` | Live Slack workspace test: POLLING outbox relay + APPLICATION_EVENT publisher (no Kafka/Debezium), MariaDB defaults, port 9000, `SLACK_SIGNING_SECRET` required (no default), health `show-details: when_authorized` because the port is tunnelled to the internet |
| `banner.txt` | Spring Boot startup banner |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `db/migration/` | `V1__` … `V23__` SQL migrations (outbox and its indexes/attempt and send counts/`MEDIUMTEXT` payload, meeting, standup, agenda dispatch, agent session/turn history, user command roles, MCP tool call history, CVE tables and indexes, data fixes) — see `db/migration/AGENTS.md` |
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
- **`server.forward-headers-strategy: none` is a safety default, not a proxy setting.** Left unset, Boot
  switches forward headers on by itself whenever it detects Kubernetes, and Jetty then takes `remoteAddr`
  from a client-supplied `X-Forwarded-For`, which defeats `McpTurnTokenFilter`'s loopback check. No code reads
  the scheme, host or client IP from forwarded headers. If something ever needs them, scope a
  `ForwardedHeaderFilter` to that path instead of changing the global strategy; `McpTurnTokenFilterTest`
  fails if any profile turns them back on.
- **Migrations are incremental patches, not a full schema.** Local runs rely on
  `ddl-auto: update` to create base tables, then the `V*` scripts patch them. Prod runs `ddl-auto: none`.
  So: a new migration must be additive and safe against a Hibernate-created base table, and any new
  entity needs *both* a JPA schema class and a migration.
- **Never renumber or edit an applied migration.** The highest number in the working tree on 2026-10-01 is
  `V23__`, so the next free one is `V24__`. Check the highest existing `V*` here and on `origin/main` before
  naming a new one.
- **Prod config is env-var only** (`${SQL_DATABASE_URL}`, `${MCP_ENABLED}`, ...). Do not commit a literal
  secret or host here; add the variable to `k8s/configmap.yaml` / `k8s/secret.yaml` instead.
- **`k8s/deployment.yaml` is templated with `envsubst '${IMAGE_NAME}'`** by `.github/workflows/deploy_action.yaml`.
  Keep the placeholder syntax intact or the deploy breaks.
- **Kafka consumer sizing** (`local`, `dev`, `prod`): `max-poll-records: 5` and `max.poll.interval.ms: 300000`,
  i.e. 300s for a batch of 5, 60s per record on average. A PENDING-row record costs one Slack dispatch: up to 3
  `RetryService` attempts, plus, after a 429 with `Retry-After` <= 3s, that wait and a second round of up to 3.
  Every Slack call (SDK and `response_url`) shares the SDK client's call timeout, `SLACK_CALL_TIMEOUT` (6s) in
  `ApplicationMessageDispatcher`, so one dispatch is bounded by 2 x (3 x 6s + retry backoff) + 3s, about 40s; rendering
  adds at most one profile lookup (3s connect + 10s read), about 53s in total, which leaves about 7s of the 60s
  for the claim, renew and completion SQL. Redo this arithmetic whenever that timeout, the retry policy or these two values change,
  and before adding any wait inside the listener. If a batch does overrun, the consumer leaves the group and the
  records are redelivered; an already-claimed row is no longer PENDING, so the CDC processor skips it.
- **Open-in-view is off on purpose** (`application.yaml`, review T25). Boot's default binds one `EntityManager` to
  each HTTP request; the meeting write that `MeetingWriteDeferral` runs after the interaction transaction commits
  would then open its first attempt on the interaction's persistence context and could answer from entities that
  transaction loaded. No code path needs it: every LAZY association (meeting participants, standup members,
  dispatches and answers, the reminder's and dispatch's parent) is fetch-joined by its read query or touched inside
  a `@Transactional` repository method, and repositories hand out DTOs only. Keep it that way — a new lazy access
  outside a transaction now fails with `LazyInitializationException` instead of quietly holding a connection.
  `MeetingWriteJpaTransactionTest` fails if a profile turns it back on.
- **Hikari pool** (`maximum-pool-size: 20` in every profile). An HTTP request holds at most one connection at a
  time: with open-in-view off each transaction returns its connection at commit, and a meeting
  cancel/reschedule/add-participant runs its `REQUIRES_NEW` write only after the interaction transaction has
  committed. (A caller that runs such a write inline inside its own transaction takes a second connection; no
  production caller does — see `service/meeting/AGENTS.md`.) The relay executor, the schedulers and the CDC
  listener share the same pool. Size it as: concurrent interactions + relay workers (`relayTaskExecutor`, 4; overflow is rejected, never run
  on the submitting thread) + scheduler threads (`spring.task.scheduling.pool.size`, 4) + CDC listener threads
  (1) + async-executor tasks that use the DB (`threadPoolTaskExecutor`, up to 10). Request threads are virtual,
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
