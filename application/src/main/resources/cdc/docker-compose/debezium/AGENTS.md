<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# cdc/docker-compose/debezium

## Purpose
Registers the Debezium MariaDB source connector that turns `outbox_message` writes into Kafka records. The
script is the single source of truth for the connector configuration in this repository; there is no
equivalent manifest for the Kubernetes side.

## Key Files
| File | Description |
|------|-------------|
| `connect_mariadb.sh` | `curl -X POST` of a connector JSON to the Connect REST `URL`. Connector `mariadb-event-connector`, class `io.debezium.connector.mariadb.MariaDbConnector`, `database.hostname: mariadb` (compose service name), `database.include.list: code_companion`, `table.include.list: code_companion.outbox_message`, `topic.prefix: cdc`, `heartbeat.interval.ms: 10000`, schema-history topic `schema-history.code_companion.outbox_message`, `database.ssl.mode: disabled`, `column.propagate.source.type: true`, `schema.history.internal.store.only.captured.tables.ddl: true` |

## For AI Agents

### Working In This Directory
- **Placeholders, by key:** `URL` host (`debezium_hostname:port`, normally `localhost:8083` from the host),
  `database.user` (`DATABASE_USER_ID`), `database.password` (`DATABASE_PASSWD`), `database.server.id`
  (`DATABASE_SERVER_ID`), and `schema.history.internal.kafka.bootstrap.servers` (`kafka_host:port`, which
  must be an in-network address such as `kafka-00:19092` because Connect resolves it from inside the
  compose network). Keep the credentials out of git; the script is copied and edited locally.
- **`database.server.id` must be unique in the replication topology.** Debezium registers as a replica
  under this id, so it must differ from the `SERVER_ID` in `../mariadb/my.cnf`. README currently says "same
  as MariaDB server-id"; pick a distinct value.
- **Topic naming is derived, not configured.** `topic.prefix` + database + table gives
  `cdc.code_companion.outbox_message`, which is what `slack.app.mode.cdc.topic` expects in the `local`,
  `dev` and `prod` profiles. Changing `topic.prefix` or capturing a second table changes topic names and
  requires a matching profile change.
- `database.server.name` is a pre-Debezium-2.0 key kept alongside `topic.prefix`; the 3.0 connector ignores
  it, so `topic.prefix` is the one that matters.
- **POST is create-only.** Re-running the script against an existing connector returns HTTP 409; to change a
  running connector use `PUT /connectors/mariadb-event-connector/config` with the `config` object, or delete
  it first. Because the connector config lives in the `DEBEZIUM_CONNECT_CONFIGS` topic, it disappears with
  the brokers on `docker compose down` and must be registered again.
- The database user needs `REPLICATION SLAVE`, `REPLICATION CLIENT`, `SELECT` and `RELOAD`; the compose
  file only creates `root`, so either use root locally or create the user before running this.
- **Why the heartbeat:** the connector commits its binlog offset only when it emits a record. With
  `outbox_message` quiet and other tables busy, the stored position stops moving while the server keeps
  purging binlogs (`expire_logs_days=7` in the cluster MariaDB config), and after a restart the connector
  cannot resume. A heartbeat every 10 s emits to `__debezium-heartbeat.cdc` and commits the current position.
  Nothing in the application consumes that topic.
- **Runbook, lost binlog position** (task `FAILED` with an error that the binlog position is no longer
  available). Not rehearsed in this repository; the options are from the Debezium reference.
  1. `PUT /connectors/mariadb-event-connector/config` with the current config plus `"snapshot.mode":
     "when_needed"`, then restart the task. The connector snapshots `outbox_message` and streams from the
     current position.
  2. The snapshot emits every row as a read (`op=r`). `DebeziumLogTailingProcessor` has no operation filter:
     non-PENDING after-images are skipped and a PENDING row is claimed by the same CAS as the recovery sweep,
     so a snapshot read cannot win a row that the sweep or the stream has already claimed.
  3. Rows written during the outage were already delivered by `OutboxRecoveryScheduler`, which claims PENDING
     rows older than `slack.app.outbox.polling.stuck-in-progress-seconds` in every mode. Watch
     `outbox_pending_oldest_age_seconds` fall back to near zero.
  4. Leave `snapshot.mode` as it was afterwards, or keep `when_needed` deliberately; `no_data` is the
     alternative when the table is large, since the sweep covers PENDING rows anyway.
- `schema.history.internal.store.only.captured.tables.ddl: true` keeps the history topic small but means DDL
  on non-captured tables is not tracked; adding a table to `table.include.list` later may need a fresh
  snapshot.
- The script has the same CI and packaging caveats as its parent: it is treated as application source by
  the workflow path filters and ends up inside the boot jar.

### Testing Requirements
- `curl localhost:8083/connectors/mariadb-event-connector/status` must show the connector and its task as
  `RUNNING` (the application cannot see this; poll it from the cluster's monitoring, or alert on
  `outbox_pending_oldest_age_seconds`, which rises when the connector stops); a `FAILED` task with a binlog error usually means `my.cnf` was not mounted (`log_bin` off) or
  the server-id collides.
- Insert a row into `code_companion.outbox_message` and confirm a record on
  `cdc.code_companion.outbox_message`; Kafka UI on `localhost:9090` lists it under topics prefixed `cdc.`.

### Common Patterns
- Connector JSON is built with a quoted heredoc into `PAYLOAD` and posted with `Content-Type:
  application/json`; extend it by adding keys inside `config`, never by string concatenation.

## Dependencies

### Internal
- `../docker-compose.yml` — the `mariadb` hostname, Kafka in-network listeners and the `debezium` service
  on 8083 this script targets
- `../mariadb/my.cnf` — binlog settings and the `SERVER_ID` this connector must not reuse
- `../../../application-local.yaml` — `slack.app.mode.cdc.topic` must equal the derived topic name

### External
Debezium Connect 3.0 REST API, Debezium MariaDB connector, `curl`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
