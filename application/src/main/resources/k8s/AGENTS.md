<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# k8s

## Purpose
Kubernetes manifests for the Code Companion application itself: the Deployment the deploy workflow
re-applies on every merge to `main`, plus the one-time ConfigMap, Secret, Service, and routing objects
that an operator applies by hand. `README.md` (English + Korean) is the operator runbook; this file only
adds what an agent editing the manifests needs to know.

## Key Files
| File | Description |
|------|-------------|
| `README.md` | Apply order, the one-time no-overlap release procedure with the V18–V23 migration checklist (V21 after the rollout; the same order as `../db/migration/AGENTS.md`), prerequisites (`dockercred` pull secret, zoneinfo on nodes), routing choice, optional agent-sidecar setup |
| `deployment.yaml` | Deployment `code-companion-deploy` (2 replicas, `image: $IMAGE_NAME`, containerPort 80, `envFrom` Secret + ConfigMap, `hostPath` `/etc/localtime` mount, `terminationGracePeriodSeconds: 100`, `preStop` `sleep 5`, container `securityContext.allowPrivilegeEscalation: false`, startup/readiness/liveness probes on `/actuator/health/{liveness,readiness}`, `resources` 250m/1536Mi requests and 2Gi memory limit) and PodDisruptionBudget `code-companion-pdb` (`minAvailable: 1`) |
| `service.yaml` | ClusterIP Service `code-companion-svc`, port 80 → 80, selector `app: code-companion-deploy` |
| `configmap.yaml` | ConfigMap `code-companion-configmap`: `SQL_PROD_ISOLATION_LEVEL`, `SQL_PROD_CONNECTION_TIMEOUT`, `SQL_PROD_VALIDATION_TIMEOUT`, `HIBERNATE_DEFAULT_BATCH_SIZE`, `KAFKA_BOOTSTRAP_SERVERS` (placeholder), `SLACK_CDC_TOPIC` (`cdc.code_companion.outbox_message`, the Debezium `topic.prefix: cdc` name) |
| `secret.yaml` | Opaque Secret `code-companion-secret` under `stringData:` (plain values, the API server encodes them) with placeholders for `SQL_DATABASE_URL`, `SQL_DATABASE_USERNAME`, `SQL_DATABASE_PASSWORD`, `SLACK_API_TOKEN`, `SLACK_SIGNING_SECRET` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `route/` | Mutually exclusive exposure options: Gateway API `httpRoute.yaml` or NGINX `ingress.yaml` (see `route/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **CI applies `deployment.yaml` only.** `.github/workflows/deploy_action.yaml` runs
  `envsubst '${IMAGE_NAME}' < application/src/main/resources/k8s/deployment.yaml | kubectl apply -n api-service -f -` with
  `IMAGE_NAME=<registry>/bot/code-companion:<commit sha>`. ConfigMap, Secret, Service and route objects are
  never touched by CI — a new key in `configmap.yaml`/`secret.yaml` needs a manual `kubectl apply` before
  the next rollout, and the manifest in git is only a template of what the cluster holds.
- **`envsubst` is restricted to `'${IMAGE_NAME}'`**, so any other `$NAME` in `deployment.yaml` survives
  verbatim (the job env holds the OCI private key, which is why the list is explicit). Still keep `$IMAGE_NAME`
  the only `$` in that file: a new placeholder needs the workflow's variable list extended too.
- **Only `route/httpRoute.yaml` sets `metadata.namespace`** (placeholder `your-namespace`); none of the manifests
  in this directory does. Every workflow step, including the apply, passes `-n api-service`, so the kubeconfig
  context namespace does not matter; a manual `kubectl apply` of the other manifests must pass the same `-n`, and
  the HTTPRoute's placeholder must be set to `api-service` first, or `kubectl apply -n api-service` rejects the
  namespace mismatch.
- **Env-var coverage vs `application-prod.yaml`.** Keys without a default there must come from these two
  manifests: the six `SQL_*` keys (three `SQL_PROD_*` in `configmap.yaml`, three `SQL_DATABASE_*` in
  `secret.yaml`), `HIBERNATE_DEFAULT_BATCH_SIZE`, `KAFKA_BOOTSTRAP_SERVERS`,
  `SLACK_API_TOKEN`, `SLACK_SIGNING_SECRET`, `SLACK_CDC_TOPIC`. All of them are present in the samples
  (credentials in `secret.yaml`, the rest in `configmap.yaml`). Everything else the profile reads (`MCP_ENABLED`, `MCP_SIGNING_SECRET`, `SIDECAR_*`,
  `AI_PROVIDER`, `GITHUB_TOKEN`, `GITHUB_RELEASES_PER_PAGE`, `NVD_*`, `CVE_COLLECTOR_*`) has a default and is
  opt-in. `VERSION`, `BUILD_DATE`, `GIT_REF`, `BUILD_NUMBER` are not manifest keys: the deploy workflow passes
  them as Docker build args and `application/Dockerfile` bakes them into the image env (`BUILD_DATE` is the
  merged PR's `merged_at`; the workflow runs on `pull_request`, whose payload has no `head_commit`).
  The management base path is no longer an env var: it is fixed at `/actuator` in `application-prod.yaml`. A
  cluster ConfigMap that still carries `ACTUATOR_BASE_PATH` is harmless; nothing reads it.
- **A missing env var is usually not a binding error for String properties.** The binder keeps an unresolvable
  `${X}` literally, so an absent `KAFKA_BOOTSTRAP_SERVERS` or `SQL_DATABASE_URL` starts the context with the
  literal text and fails later, when the Kafka client or the datasource uses it. Keys bound to a non-String type
  (the Hikari timeouts) do fail binding. Two keys fail startup instead: `SLACK_SIGNING_SECRET` by design
  (`SlackRequestVerificationFilter` rejects an unresolved or blank value outside `local`), and `SLACK_CDC_TOPIC`,
  because `DebeziumLogTailingProcessor`'s `@KafkaListener(topics = ["${slack.app.mode.cdc.topic}"])` goes through
  the context's strict embedded-value resolver, which resolves the nested `${SLACK_CDC_TOPIC}` and throws
  `Could not resolve placeholder` (read from the source, not measured). The `YOUR_KAFKA_HOST:9092` placeholder
  binds and fails only when used, so replace it in-cluster before the first rollout.
- `SLACK_CDC_TOPIC` must equal the Debezium topic (`topic.prefix` + `.` + `<db>.<table>`, see `../cdc/`). Records the
  CDC listener cannot process go to `<SLACK_CDC_TOPIC>-dlt`: the suffix is a constant in
  `KafkaConsumerConfiguration`, and the app declares that topic as a `NewTopic` bean, which `KafkaAdmin` creates at
  startup. Pre-create it only if the app's Kafka principal lacks topic-create permission.
- **Secret vs ConfigMap split:** anything credential-like goes in `secret.yaml`, everything else in
  `configmap.yaml`. Values in git stay placeholders; the deployed Secret is edited in-cluster. Note that
  `.gitleaks.toml` allowlists only the CDC MariaDB sample Secret, not this one — the `YOUR_*` placeholders
  pass, but realistic-looking sample values would trip the `secret-scan` job.
- **Probes and the shutdown budget go together.** On deletion the `preStop` hook sleeps 5s (endpoint removal
  reaches kube-proxy and the gateway asynchronously), then SIGTERM starts Spring's graceful shutdown. The relay stops
  first and drains its queue (queued claims stay `IN_PROGRESS` for another pod's sweep). Then the lifecycle phases run
  one after another: the Kafka listener phase waits for the record in hand for `RECORD_SHUTDOWN_WAIT`
  (`configurations/AsyncConfig.kt`: one dispatch, `RELAY_RECORD_TIME_BOUND` rounded up — derived in code from the
  profile lookup's whole-call timeout, `SLACK_DISPATCH_TIME_BOUND` and the status write's retry backoff, 46s today;
  the CDC-mode `lifecycleProcessor` bean sets that phase alone), while the web server drain and the
  `ThreadPoolTaskScheduler` with a running job keep `spring.lifecycle.timeout-per-shutdown-phase` (10s in
  `application-prod.yaml`). Three Kafka producer closes are not bounded by any phase timeout (the JSON producer
  factory closes synchronously in its own `stop()`, the two dead-letter producers, JSON and bytes, in
  `CdcDeadLetterRecovery.destroy()`) and wait for unsent records, so each is capped at
  `PRODUCER_CLOSE_TIMEOUT_SECONDS` (5s) instead of spring-kafka's 30s. Then the destroy-time executor waits run one
  after another, all before the EntityManagerFactory and the DataSource close (`@DependsOn("entityManagerFactory")` on
  the relay and agent-turn executors): relay `RECORD_SHUTDOWN_WAIT`, agent turns 20s
  (`slack.app.agent.turns.shutdown-await-seconds`), default 10s. `terminationGracePeriodSeconds` (180) must cover
  5 + 2 x 10 + 46 + 3 x 5 + 46 + 20 + 10 = 162, leaving 18s; `configurations/ShutdownBudgetTest` recomputes every
  term from code, this manifest and the prod profile and fails when the grace leaves less than a 10s margin. So a dispatch running at
  SIGTERM, on the listener thread or a relay thread, finishes and records its status before the DataSource closes,
  with a healthy pool; a starved pool adds a `connection-timeout` per statement that the budget does not cover
  (`service/relay/AGENTS.md`, "Per-record time budget"). Not counted, and only inferred from source: a poison record
  in its retry back-off can create one more dead-letter producer to close, and HikariCP's pool shutdown can wait
  several seconds when the database is unreachable; both come out of the margin. A crash or SIGKILL still cuts a
  dispatch, and the sweep can then post it twice. `management.endpoint.health.probes.enabled: true` in the prod profile is what makes
  `/actuator/health/{liveness,readiness}` exist. The startup probe allows 36 × 5s = 3 minutes.
- **Memory:** the Dockerfile's `-XX:MaxRAMPercentage=50.0` makes the heap 1Gi of the 2Gi limit. Metaspace, code
  cache, thread stacks and direct buffers (Jetty, Kafka, MariaDB driver) come on top, so the 1536Mi request is
  sized for heap + non-heap; a request equal to the heap would leave the Pod above its request and first in line
  for node-pressure eviction. Change the request, the limit and the percentage together.
- **Strategy `Recreate`, on purpose and temporarily.** The outbox claim-token release (V20) must never run beside a
  pre-V20 Pod, which re-dispatches `IN_PROGRESS` rows it does not own, so `deployment.yaml` sets
  `strategy.type: Recreate` (with `rollingUpdate: null`, which clears the API server's defaulted block). Every
  rollout and every `rollout undo` stops the old Pods before the first new one starts, and the PDB does not apply
  (the ReplicaSet deletes the Pods, not the eviction API). Cost: each deploy is an outage of the old Pods' shutdown
  (at most the 180s grace) plus the new Pod's startup (at most the 180s startup probe) and first readiness check
  (10s); the workflow's 450s rollout timeout and 25-minute deploy job are sized from those numbers, and
  `configurations/ShutdownBudgetTest` fails if the rollout timeout drops below them. Requests need only
  2 × 1536Mi. Remove the block in a follow-up PR once no pre-V20 revision is wanted (`README.md`, "Afterwards");
  the default RollingUpdate then runs 3 Pods during a rollout (3 × 1536Mi = 4.5Gi of requested memory at once) —
  check with `kubectl describe nodes | grep -A8 'Allocated resources'`; a Pod that cannot be scheduled stays
  `Pending`, `rollout status` times out and the workflow rolls back.
- **Open decision — Slack retry dedup across replicas.** `SlackRetryDeduplicator` keeps its state in one JVM,
  while this Deployment runs 2 replicas (3 during a rollout), so a Slack retry routed to the other Pod is processed
  again. Two options, not yet chosen (`docs/wiki/decisions.md` #34):
  1. Shared `event_id` store (a DB table with a unique key and an atomic state transition): works with any replica
     count, costs a migration, one DB round-trip per Slack request inside the 3s ack budget, and a retention job.
  2. A single replica: no new code, but no redundancy, and a rollout still overlaps two Pods unless the strategy
     is `Recreate` (downtime on every deploy); `replicas: 1` also makes the PDB `minAvailable: 1` block node drains.
  Do not change `replicas` until the decision is made.
- **Open item — the container runs as root.** It binds port 80 and the image has no `USER`; only
  `allowPrivilegeEscalation: false` is set. Moving to non-root needs a port change (or `NET_BIND_SERVICE`) across
  the Dockerfile, `server.port`, the probes and `service.yaml`, so it was deferred.
- Port 80 is fixed in three places that must move together: `containerPort` here, `server.port` in
  `application-prod.yaml`, and `SERVER_PORT` in `application/Dockerfile`. `service.yaml` targets it by number.
- `imagePullPolicy: IfNotPresent` is safe only because the workflow tags every image with the commit sha;
  never point `$IMAGE_NAME` at a mutable tag.
- The timezone is a `hostPath` mount of `/usr/share/zoneinfo/Asia/Seoul`; a node without that file cannot
  schedule the Pod (see README prerequisites). The JVM also gets `-Duser.timezone=Asia/Seoul` from the
  Dockerfile, so both must agree.
- The agent sidecar (`@bot ask`) is intentionally absent from `deployment.yaml`; README describes how to add
  it as a native sidecar `initContainer` and which Secret keys it needs.
- **The deploy health check is in-cluster and gates on readiness.** The workflow polls
  `/actuator/health/readiness` through the API server's service proxy
  (`/api/v1/namespaces/api-service/services/code-companion-svc:80/proxy/...`, RBAC `get` on `services/proxy`,
  resource name `code-companion-svc:80` if the Role uses `resourceNames`) and falls back to
  `kubectl exec <pod> -c code-companion-deploy -- wget -qO- http://localhost:80/...` on every non-terminating
  Pod of the current revision, found through the ReplicaSet's `pod-template-hash` label (RBAC `create` on
  `pods/exec`, `list` on `replicasets` and `pods`). The aggregate `/actuator/health` is logged but does not gate.
  Renaming the Service, its port, the Deployment or the container, changing the `app` label, or moving the
  management base path, needs the same change in `deploy_action.yaml`. It never uses the public host: that host is fronted by a bearer-authenticating layer
  outside this repository (on 2026-09-28 every probed path, nonexistent ones included, answered `401` with
  `WWW-Authenticate: Bearer`, except `GET /actuator/health`, which answered a `404` JSON body that is not this
  application's error format), and what it forwards to the app has
  to be confirmed by whoever operates it.
- **`/actuator` must never be routed publicly** (unauthenticated `metrics`/`prometheus`/`info`, and `health`
  reports outbox state). Prometheus scrapes `/actuator/prometheus` on the Pod port 80 from inside the cluster;
  the Deployment carries no scrape annotations, so add whatever discovery the cluster's Prometheus uses. The samples in `route/` therefore forward only `/api/slack` and `/api/slash`; `/api` as a whole would
  expose `/api/actuator` if a sample were reused with the dev, local or slack-live profile.
- **CI path filters treat these YAML files as source.** Only `**/*.md` is excluded, so a manifest-only push
  runs lint + `:application:test`, and a manifest-only PR merged to `main` builds and deploys a new image.
- Everything here is packaged into the boot jar by `processResources` even though the app never reads it.

### Testing Requirements
- There is no unit or integration test for manifests. Validate locally with
  `IMAGE_NAME=example envsubst '${IMAGE_NAME}' < deployment.yaml | kubectl apply --dry-run=server -f -` and
  `kubectl apply --dry-run=client -f <file>` for the rest.
- The deploy workflow is the real check: rollout status (450s), ready-pod count at least `spec.replicas`, then
  up to ~2 minutes of in-cluster readiness checks; a failure of one of those steps (or of the apply) that left the
  pod template or the revision different from the pre-apply backup triggers
  `kubectl rollout undo --to-revision=<previous>`, which restores the whole previous pod template.
- After changing `configmap.yaml`/`secret.yaml` keys, confirm `application-prod.yaml` resolves every
  `${KEY}` without a default. Most missing String keys do not stop startup (see above, `SLACK_SIGNING_SECRET` and
  `SLACK_CDC_TOPIC` excepted), so the gap shows up only at runtime.

### Common Patterns
- One object per file, all named `code-companion-*`, all selected by the label `app: code-companion-deploy`.
- Environment injection is `envFrom` (whole Secret + whole ConfigMap), never per-key `env` entries.
- Placeholders are `YOUR_*` (Secret values), `$IMAGE_NAME` (CI substitution) and `your-*` / `your.uri`
  (routing); keep those spellings so README instructions stay accurate.

## Dependencies

### Internal
- `../application-prod.yaml` — the profile these env vars feed; `server.port: 80`
- `application/Dockerfile` — image entrypoint, `PROFILE=prod`, build-info env vars, `EXPOSE 80`
- `.github/workflows/deploy_action.yaml` — builds the image, applies `deployment.yaml`, verifies in-cluster, rolls back
- `.gitleaks.toml` — secret-scan allowlist (does not cover `secret.yaml`)

### External
Kubernetes 1.31+ (OKE), Harbor registry via the `dockercred` pull secret, GNU `envsubst`, cert-manager and
either the Gateway API CRDs or the NGINX Ingress Controller for `route/`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
