<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# cdc/docker-compose

## Purpose
Local, development-only CDC stack: one MariaDB with binlog enabled, a 3-node KRaft Kafka cluster, Debezium
Connect, and two web UIs. Registering `debezium/connect_mariadb.sh` afterwards makes every write to
`code_companion.outbox_message` appear on the `cdc.code_companion.outbox_message` topic that the app's
`cdc` outbox-reading strategy consumes. `README.md` (English + Korean) is the step-by-step guide; this file
records what the compose file actually wires and where it bites.

## Key Files
| File | Description |
|------|-------------|
| `README.md` | Placeholder list, start/verify/cleanup commands, port table, how to point `application-local.yaml` at this stack, troubleshooting |
| `docker-compose.yml` | Services `mariadb` (untagged `mariadb` image, database `code_companion`, `my.cnf` bind-mounted into `/etc/mysql/conf.d/`), `kafka-00/01/02` (`apache/kafka:3.8.1`, combined broker+controller, listeners `PLAINTEXT` 1909x in-network, `CONTROLLER` 2909x, `EXTERNAL` 909x published to the host), `kafka-ui` (`provectuslabs/kafka-ui:latest` on 9090, login form), `debezium` (`debezium/connect:3.0.0.Final` on 8083, JSON converters, group `debezium-00`, `DEBEZIUM_CONNECT_*` storage topics), `debezium-ui` (`debezium/debezium-ui:2.2` on 9091); all on the `local-infra` bridge network |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `debezium/` | `connect_mariadb.sh` — registers the MariaDB source connector over the Connect REST API (see `debezium/AGENTS.md`) |
| `mariadb/` | `my.cnf` — the minimum binlog settings Debezium needs (see `mariadb/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Placeholders to substitute in `docker-compose.yml`, by key:** `EXTERNAL_IP` (host part of every
  `KAFKA_ADVERTISED_LISTENERS` `EXTERNAL://` entry; `localhost` for a single-machine setup),
  `MARIADB_ROOT_PASSWORD`, `SPRING_SECURITY_USER_NAME` / `SPRING_SECURITY_USER_PASSWORD` (Kafka UI login),
  and the two host paths `/your/cnf/location/my.cnf` and `/your/kafka/config`. Never commit real values;
  the file is copied, not consumed in place.
- **Three listener tiers, and only one is reachable from the host.** `9092`–`9094` are the `EXTERNAL`
  listeners published by `ports:`; `19092`–`19094` are container-to-container (`PLAINTEXT`, what Debezium and
  Kafka UI use); `29092`–`29094` are KRaft controller ports. `application-local.yaml` ships pointing at a
  different local Kafka (`~/infra`, ports `19092/29092/39092`), so connecting the app to this stack means
  editing `spring.kafka.bootstrap-servers` to `localhost:9092`–`9094` — the README shows the snippet.
- **Nothing persists.** `mariadb` has no data volume and Kafka only bind-mounts config directories, so
  `docker compose down` discards the database and topics; `down -v` is the same as `down` here.
- **The connector is not part of `up`.** Until `debezium/connect_mariadb.sh` has been run against
  `localhost:8083` the CDC topic does not exist and the relay in `cdc` mode sits idle; a profile that omits
  `slack.app.mode.outbox-reading-strategy` polls the table instead and never notices.
- `CLUSTER_ID` is the same literal on all three brokers on purpose; changing it on one node splits the
  quorum. `KAFKA_CONTROLLER_QUORUM_VOTERS` hard-codes the three service names, so renaming a service means
  editing every broker block.
- Image pinning is inconsistent: Kafka and Debezium are pinned, `mariadb` and `kafka-ui` float on `latest`.
  The in-cluster manifests under `../k8s/yamls/mariadb/` pin `mariadb:12.0.2`; match that when a MariaDB
  behaviour difference shows up.
- Debezium Connect stores connector config, offsets and status in Kafka (`DEBEZIUM_CONNECT_*` topics), so
  re-registering after a `down` is required — the topics were discarded with the brokers.
- **CI treats every non-`.md` file here as application source.** A push touching `docker-compose.yml` runs
  lint + `:application:test`; merged to `main` it triggers a production image build and deploy, even though
  the running app never reads this directory. The files are also packaged into the boot jar by
  `processResources`.

### Testing Requirements
- No automated tests. Smoke-check with `docker compose config` (placeholder syntax), `docker compose ps`
  (all six services up), `curl localhost:8083/connectors/mariadb-event-connector/status` (`RUNNING`), then an
  insert into `outbox_message` followed by a console consumer on `cdc.code_companion.outbox_message`.
- `SHOW VARIABLES LIKE 'log_bin'` on the MariaDB container must report `ON`; if not, the `my.cnf` bind mount
  path is wrong and the connector will fail at snapshot.

### Common Patterns
- Placeholders are SCREAMING_SNAKE (`EXTERNAL_IP`, `ROOT_PASSWORD`, `KAFKA_UI_USER_*`) or `/your/...` paths;
  README lists them by exact spelling, so keep it that way.
- Ports follow one scheme per broker index (`909x` external, `1909x` internal, `2909x` controller).

## Dependencies

### Internal
- `../../application-local.yaml` — `spring.kafka.bootstrap-servers`, `slack.app.mode.cdc.topic`,
  `slack.app.mode.outbox-reading-strategy` the stack has to line up with
- `infrastructure` outbox relay — consumer of the CDC topic when the reading strategy is `cdc`
- `../k8s/yamls/mariadb/` — the in-cluster counterpart of the `mariadb` service and `my.cnf`

### External
Docker Engine 20.10+ / Compose 2.0+, `apache/kafka:3.8.1`, `debezium/connect:3.0.0.Final`,
`debezium/debezium-ui:2.2`, `provectuslabs/kafka-ui`, `mariadb`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
