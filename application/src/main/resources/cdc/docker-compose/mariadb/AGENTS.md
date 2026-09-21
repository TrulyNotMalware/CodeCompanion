<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# cdc/docker-compose/mariadb

## Purpose
The MariaDB server configuration the local CDC stack bind-mounts into the `mariadb` container. It carries
only what Debezium needs to read the binlog; everything else stays at image defaults.

## Key Files
| File | Description |
|------|-------------|
| `my.cnf` | `[mysqld]` block: `server-id=SERVER_ID` (placeholder), `log-bin=binlog`, `binlog_format=ROW`, `binlog_row_image=FULL` |

## For AI Agents

### Working In This Directory
- **`SERVER_ID` is the only placeholder.** Any positive integer works locally; it must differ from the
  `database.server.id` given to the Debezium connector in `../debezium/connect_mariadb.sh`.
- **All three binlog settings are mandatory for Debezium.** `ROW` format and `FULL` row image are what let
  the connector emit complete before/after images of `outbox_message`; `STATEMENT`/`MIXED` or `MINIMAL`
  break the relay silently (events arrive with missing columns).
- The file is mounted as `/etc/mysql/conf.d/my.cnf` via the `/your/cnf/location/my.cnf` placeholder in
  `../docker-compose.yml`. If that host path does not exist Docker creates a directory of that name, the
  mount succeeds, MariaDB ignores it, and `log_bin` stays `OFF` — the first symptom is the connector failing
  at snapshot.
- Retention is the image default (no `expire_logs_days` here); the in-cluster `master.cnf` under
  `../../k8s/yamls/mariadb/mariadb-config.yaml` adds retention, GTID and InnoDB tuning on top of these same
  three lines, so keep the two in step when changing binlog options.
- Same CI and packaging caveats as the parent: a change here counts as application source for the workflow
  path filters, and the file is packaged into the boot jar.

### Testing Requirements
- After `docker compose up`, `SHOW VARIABLES LIKE 'log_bin'` must be `ON` and `SHOW VARIABLES LIKE
  'binlog_format'` must be `ROW`; `SELECT @@server_id` must return the substituted value.

### Common Patterns
- One `[mysqld]` section, one option per line, placeholder in SCREAMING_SNAKE.

## Dependencies

### Internal
- `../docker-compose.yml` — bind-mount source path placeholder
- `../debezium/connect_mariadb.sh` — consumer of the binlog these settings enable
- `../../k8s/yamls/mariadb/mariadb-config.yaml` — superset of this file for the in-cluster database

### External
MariaDB server (`mariadb` image).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
