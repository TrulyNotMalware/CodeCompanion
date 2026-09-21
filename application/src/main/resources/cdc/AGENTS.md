<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# cdc

## Purpose
Change-data-capture infrastructure for the outbox relay: a local Docker Compose stack (MariaDB with binlog,
3-node KRaft Kafka, Debezium Connect) and the in-cluster MariaDB manifests that play the same source-database
role in Kubernetes. Nothing under this tree is read by the application at runtime; it is operator tooling
that happens to live under `src/main/resources`, so `processResources` still packages every file here
(READMEs and AGENTS.md included) into the boot jar.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `docker-compose/` | Local CDC stack: compose file, Debezium connector registration script, MariaDB binlog config (see `docker-compose/AGENTS.md`) |
| `k8s/` | Kubernetes manifests for the CDC source database (see `k8s/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
