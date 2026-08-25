<!-- Parent: ../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# application/resources

## Purpose
Runtime configuration and deployment assets: the Spring profile YAML tree, the versioned SQL migration
set, the Kubernetes manifests the deploy workflow applies, and the Debezium/MariaDB CDC stack used to
stand up change-data-capture locally and in-cluster.

## Key Files
| File | Description |
|------|-------------|
| `application.yaml` | Base defaults only — kept deliberately minimal. MCP server off by default; scheduler pool sized to 4 |
| `application-local.yaml` | Local orbstack infra: MariaDB on 3306, 3-broker Kafka on 19092/29092/39092, virtual threads on, Flyway **off** + `ddl-auto: update`, `show-sql: true` |
| `application-dev.yaml` | Development environment |
| `application-real.yaml` | Real-infrastructure integration runs; Flyway enabled |
| `application-prod.yaml` | Production: everything env-var driven, `ddl-auto: none`, `show-sql: false`, 10s graceful shutdown, H2 console off |
| `banner.txt` | Spring Boot startup banner |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `db/migration/` | `V1__` … `V17__` SQL migrations (outbox, meeting, standup, agenda dispatch, agent session/turn history, user command roles, MCP tool call history, CVE tables and indexes) |
| `k8s/` | `deployment.yaml`, `service.yaml`, `configmap.yaml`, `secret.yaml` + `route/` (`ingress.yaml`, `httpRoute.yaml`) — see `k8s/README.md` |
| `cdc/docker-compose/` | Local Debezium + MariaDB stack (`docker-compose.yml`, `debezium/connect_mariadb.sh`, `mariadb/my.cnf`) — see its `README.md` |
| `cdc/k8s/yamls/mariadb/` | In-cluster MariaDB StatefulSet, service, config, and init job |

## For AI Agents

### Working In This Directory
- **The base `application.yaml` is intentionally thin.** Only cross-profile *safety* defaults belong
  there (e.g. MCP server disabled so the starter does not auto-expose an open `/mcp`). Environment
  detail belongs in the profile file.
- **Migrations are incremental patches, not a full schema.** Local runs keep Flyway **off** and rely on
  `ddl-auto: update` to create base tables, then the `V*` scripts patch them. Prod runs `ddl-auto: none`.
  So: a new migration must be additive and safe against a Hibernate-created base table, and any new
  entity needs *both* a JPA schema class and a migration.
- **Never renumber or edit an applied migration.** Add `V18__...` — the next free number. Check the
  highest existing `V*` before naming a new one.
- **Prod config is env-var only** (`${SQL_DATABASE_URL}`, `${MCP_ENABLED}`, ...). Do not commit a literal
  secret or host here; add the variable to `k8s/configmap.yaml` / `k8s/secret.yaml` instead.
- **`k8s/deployment.yaml` is templated with `envsubst`** by `.github/workflows/deploy_action.yaml`
  (`$IMAGE_NAME` is substituted at apply time). Keep the placeholder syntax intact or the deploy breaks.
- Every new `slack.app.*` property needs a matching default in
  `application/configurations/AppConfig.kt`, or binding silently falls back.

### Testing Requirements
- `infrastructure/src/test/resources/application.yaml` is the test profile (H2 + `EmbeddedKafka`);
  changes to entity mappings must keep it booting.
- After adding a migration, verify it applies against a `real`-profile database — H2/`ddl-auto` tests
  will *not* catch a MariaDB-specific syntax error.
- Deployment manifest changes are verified by the deploy workflow's rollout + `/actuator/health` probe;
  there is no local test for them.

### Common Patterns
- One file per profile, activated by `spring.config.activate.on-profile`.
- Virtual threads (`spring.threads.virtual.enabled: true`) are on in local and prod.
- Migration naming: `V<n>__<snake_case_description>.sql`.
- YAML comments explain *why* a setting is what it is — keep that habit; several of these settings
  (scheduler pool size, MCP default, Flyway-off locally) exist to prevent a specific failure.

## Dependencies

### Internal
- `application/configurations/AppConfig.kt` — binds the `slack.app.*` tree in these files
- `infrastructure/repository/*/schema/` — JPA schemas the migrations must stay in sync with

### External
MariaDB (prod/local) and H2 (test), Apache Kafka, Debezium connector, Kubernetes (OKE) + Harbor registry.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
