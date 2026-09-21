<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# cdc/k8s/yamls/mariadb

## Purpose
In-cluster MariaDB for the CDC source: a 3-pod StatefulSet (ordinal 0 is the master, 1 and 2 are read-only
replicas) with the binlog settings Debezium needs, the Services that address it, and a one-shot Job that
wires replication. `README.md` (English + Korean) is the apply/verify runbook; this file records what each
manifest declares and the mismatches an agent must know before editing.

## Key Files
| File | Description |
|------|-------------|
| `README.md` | Pre-deployment edits (storage class, Secret values, resources, Service type), apply order `config → sts → svc → job`, connection endpoints, verification commands |
| `mariadb-config.yaml` | Namespace `database`; ConfigMap `mariadb-config` with `master.cnf` and `slave.cnf` (binlog `ROW`/`FULL`, `expire_logs_days=7`, `gtid_strict_mode=1`, `log_slave_updates=1`, InnoDB and slow-log tuning; `slave.cnf` adds `read_only=1`, relay log, parallel replication); Secret `mariadb-secret` with placeholder `root-password` and `replication-password`; a standalone PVC `logs-mariadb-pvc` (20Gi) |
| `mariadb-sts.yaml` | StatefulSet `mariadb` (`serviceName: mariadb-headless`, 3 replicas, `mariadb:12.0.2`, preferred anti-affinity by hostname). Init container picks `master.cnf` + `server-id=1` for ordinal 0, else `slave.cnf` + `server-id=<ordinal+1000>`. Liveness/readiness/preStop via `mariadb-admin`/`mariadb`; requests 8Gi/500m, limits 16Gi/1000m; claim templates `data` 50Gi and `logs` 5Gi. PodDisruptionBudget `mariadb-pdb` (`minAvailable: 2`) |
| `mariadb-svc.yaml` | Services `mariadb-master` (selector `role: master`), `mariadb-slave` (selector `role: slave`), and headless `mariadb-headless` (`clusterIP: None`, selector `app: mariadb`), all port 3306 |
| `mariadb-job.yaml` | Job `setup-mariadb-replication` (`mariadb:12.0.2`): waits for `mariadb-0..2` over headless DNS, creates the `replicator` user on the master, runs `CHANGE MASTER TO` with the master's binlog file/position on pods 1 and 2, then writes a `test_replication` database to prove replication |

## For AI Agents

### Working In This Directory
- **Placeholders, by key:** `storageClassName` (`your-storage-class-name`, in the standalone PVC and both
  claim templates), Secret `data.root-password` and `data.replication-password` (base64 values), and the
  replication user name `replicator` (a literal `MYSQL_REPLICATION_USER` in both the StatefulSet and the
  Job — change both or the Job creates one user and the replicas use another). Never commit real base64.
- **The Secret block is allowlisted in `.gitleaks.toml`** because its SCREAMING_SNAKE placeholders trigger
  the `kubernetes-secret-yaml` rule. The allowlist is path-based (`mariadb-config.yaml` only): moving the
  Secret to another file re-triggers the scan, and replacing placeholders with real-looking values is
  exactly what the allowlist must never be used to hide.
- **`mariadb-master` and `mariadb-slave` have no endpoints as written.** They select `role: master` /
  `role: slave`, but `mariadb-sts.yaml` labels pods with `app: mariadb` only and nothing ever adds a
  `role` label. Only `mariadb-headless` (and the per-pod names
  `mariadb-<n>.mariadb-headless.database.svc.cluster.local`) resolve to a pod; the README's
  `mariadb-master.database.svc.cluster.local` endpoint works only after labelling pods by hand or adding a
  `role` label to the pod template (which would then be identical for all three ordinals).
- **Three names must stay aligned:** the headless Service name (`mariadb-headless`), `serviceName` in the
  StatefulSet, and the DNS suffix hard-coded in the Job. The 2026-08-25 repair (`47f0993`) fixed exactly
  this — the Services had used namespace `mariadb` and `mariadb-headless-svc` — so a mismatch shows up as
  the Job looping on "not ready yet" forever.
- **Replication is binlog-position based although the configs enable GTID.** The Job reads `SHOW MASTER
  STATUS` and passes `MASTER_LOG_FILE`/`MASTER_LOG_POS`; `gtid_strict_mode=1` is set but never used for the
  `CHANGE MASTER TO`. It is also a one-shot: re-running requires deleting the Job, and it leaves a
  `test_replication` database with one row on every node.
- `expire_logs_days=7` bounds how long a stopped Debezium connector can be down before its binlog position
  is gone and a new snapshot is required.
- `logs-mariadb-pvc` in `mariadb-config.yaml` is not mounted by anything; the StatefulSet uses its own
  `logs` claim template. Deleting it changes nothing for the pods.
- Everything lives in namespace `database` while the application runs in `api-service`, so the app's
  `SQL_DATABASE_URL` must use the cross-namespace DNS form, and a Debezium connector pointed here needs
  `database.hostname` set to the master pod's headless name rather than the compose-era `mariadb`.
- `minAvailable: 2` on a 3-pod set means a node drain can evict at most one pod at a time; with the
  default OrderedReady policy a rolling update of the master (ordinal 0) is last.
- **CI treats these YAML files as application source.** Only `**/*.md` is filtered out, so a manifest-only
  push runs lint + `:application:test`, and a manifest-only merge to `main` builds and deploys the app
  image. They are also packaged into the boot jar by `processResources`.

### Testing Requirements
- No automated tests. `kubectl apply --dry-run=server -f <file>` catches schema errors; the README's
  verification block (`SELECT @@server_id, @@read_only` per pod, `SHOW SLAVE STATUS\G` on 1 and 2, a
  cross-pod write/read) is the functional check.
- `kubectl logs -f job/setup-mariadb-replication -n database` must reach "Replication setup completed!";
  if it stalls on a `Waiting for mariadb-<n>` line, check the three-name alignment above first.
- After changing `master.cnf`, restart pods (`kubectl rollout restart sts/mariadb -n database`); the init
  container regenerates `server.cnf` from the ConfigMap only at pod start.

### Common Patterns
- Placeholders: `your-storage-class-name` (lower-case) for cluster-specific names, SCREAMING_SNAKE for
  Secret values, matching README wording.
- Master/replica role is a function of the StatefulSet ordinal (0 = master), derived in the init container
  from `hostname`; never hard-code a role into the pod template.
- All `mariadb`/`mariadb-admin` invocations pass `-p"$MYSQL_ROOT_PASSWORD"` from the Secret via
  `secretKeyRef`; keep credentials out of literal `command` strings.

## Dependencies

### Internal
- `../../../docker-compose/mariadb/my.cnf` — the three binlog lines `master.cnf`/`slave.cnf` build on
- `../../../docker-compose/debezium/connect_mariadb.sh` — the connector config that would be pointed at this
  database in-cluster
- `../../../../k8s/secret.yaml` — where the app's `SQL_DATABASE_URL` for this database is supplied
- `.gitleaks.toml` — allowlist entry for this directory's sample Secret

### External
Kubernetes StatefulSet/PDB/Job APIs, a `ReadWriteOnce` StorageClass, `mariadb:12.0.2` image.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
