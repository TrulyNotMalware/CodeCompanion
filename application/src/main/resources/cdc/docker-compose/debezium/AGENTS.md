<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# cdc/docker-compose/debezium

## Purpose
Registers the Debezium MariaDB source connector that turns `outbox_message` writes into Kafka records. The
script is the single source of truth for the connector configuration in this repository; there is no
equivalent manifest for the Kubernetes side.

## Key Files
| File | Description |
|------|-------------|
| `connect_mariadb.sh` | `curl -X POST` of a connector JSON to the Connect REST `URL`. Connector `mariadb-event-connector`, class `io.debezium.connector.mariadb.MariaDbConnector`, `database.hostname: mariadb` (compose service name), `database.include.list: code_companion`, `table.include.list: code_companion.outbox_message`, `topic.prefix: cdc`, schema-history topic `schema-history.code_companion.outbox_message`, `database.ssl.mode: disabled`, `column.propagate.source.type: true`, `schema.history.internal.store.only.captured.tables.ddl: true` |

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
- `schema.history.internal.store.only.captured.tables.ddl: true` keeps the history topic small but means DDL
  on non-captured tables is not tracked; adding a table to `table.include.list` later may need a fresh
  snapshot.
- The script has the same CI and packaging caveats as its parent: it is treated as application source by
  the workflow path filters and ends up inside the boot jar.

### Testing Requirements
- `curl localhost:8083/connectors/mariadb-event-connector/status` must show the connector and its task as
  `RUNNING`; a `FAILED` task with a binlog error usually means `my.cnf` was not mounted (`log_bin` off) or
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
