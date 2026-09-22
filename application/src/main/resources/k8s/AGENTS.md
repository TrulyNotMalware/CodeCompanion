<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-22 -->

# k8s

## Purpose
Kubernetes manifests for the Code Companion application itself: the Deployment the deploy workflow
re-applies on every merge to `main`, plus the one-time ConfigMap, Secret, Service, and routing objects
that an operator applies by hand. `README.md` (English + Korean) is the operator runbook; this file only
adds what an agent editing the manifests needs to know.

## Key Files
| File | Description |
|------|-------------|
| `README.md` | Apply order, prerequisites (`dockercred` pull secret, zoneinfo on nodes), routing choice, optional agent-sidecar setup |
| `deployment.yaml` | Deployment `code-companion-deploy` (2 replicas, `image: $IMAGE_NAME`, containerPort 80, `envFrom` Secret + ConfigMap, `hostPath` `/etc/localtime` mount, `terminationGracePeriodSeconds: 30`, startup/readiness/liveness probes on `/actuator/health/{liveness,readiness}`, `resources` 250m/1Gi requests and 2Gi memory limit) and PodDisruptionBudget `code-companion-pdb` (`minAvailable: 1`) |
| `service.yaml` | ClusterIP Service `code-companion-svc`, port 80 → 80, selector `app: code-companion-deploy` |
| `configmap.yaml` | ConfigMap `code-companion-configmap`: `SQL_PROD_ISOLATION_LEVEL`, `SQL_PROD_CONNECTION_TIMEOUT`, `SQL_PROD_VALIDATION_TIMEOUT`, `HIBERNATE_DEFAULT_BATCH_SIZE`, `ACTUATOR_BASE_PATH`, `KAFKA_BOOTSTRAP_SERVERS` (placeholder) |
| `secret.yaml` | Opaque Secret `code-companion-secret` with placeholder values for `SQL_DATABASE_URL`, `SQL_DATABASE_USERNAME`, `SQL_DATABASE_PASSWORD`, `SLACK_API_TOKEN` (values must be base64) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `route/` | Mutually exclusive exposure options: Gateway API `httpRoute.yaml` or NGINX `ingress.yaml` (see `route/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **CI applies `deployment.yaml` only.** `.github/workflows/deploy_action.yaml` runs
  `envsubst < application/src/main/resources/k8s/deployment.yaml | kubectl apply -n api-service -f -` with
  `IMAGE_NAME=<registry>/bot/code-companion:<commit sha>`. ConfigMap, Secret, Service and route objects are
  never touched by CI — a new key in `configmap.yaml`/`secret.yaml` needs a manual `kubectl apply` before
  the next rollout, and the manifest in git is only a template of what the cluster holds.
- **`envsubst` is called with no variable list**, so every `$NAME` / `${NAME}` in `deployment.yaml` is
  substituted (unset ones become empty). `$IMAGE_NAME` is the only intended placeholder; do not introduce a
  literal `$` anywhere else in that file.
- **No manifest sets `metadata.namespace`.** Every workflow step, including the apply, passes
  `-n api-service`, so the kubeconfig context namespace does not matter; a manual `kubectl apply` of the
  other manifests must pass the same `-n`.
- **Env-var coverage vs `application-prod.yaml`.** Keys without a default there must come from these two
  manifests: the five `SQL_*` keys, `HIBERNATE_DEFAULT_BATCH_SIZE`, `ACTUATOR_BASE_PATH`, `KAFKA_BOOTSTRAP_SERVERS`,
  `SLACK_API_TOKEN`, `SLACK_SIGNING_SECRET`, `SLACK_CDC_TOPIC`. The sample manifests omit `SLACK_SIGNING_SECRET` (belongs in
  `secret.yaml`) and `SLACK_CDC_TOPIC` (belongs in `configmap.yaml`); a Pod started from them as-is fails
  property binding. Everything else the profile reads (`MCP_ENABLED`, `MCP_SIGNING_SECRET`, `SIDECAR_*`,
  `AI_PROVIDER`, `GITHUB_TOKEN`, `GITHUB_RELEASES_PER_PAGE`, `NVD_*`, `CVE_COLLECTOR_*`) has a default and is
  opt-in. `VERSION`, `BUILD_DATE`, `GIT_REF`, `BUILD_NUMBER` are baked in by `application/Dockerfile`.
- `spring.kafka.bootstrap-servers` in `application-prod.yaml` is `${KAFKA_BOOTSTRAP_SERVERS}` with no
  default. Property binding only fails when the env var is *absent*; the `YOUR_KAFKA_HOST:9092` placeholder
  in `configmap.yaml` binds fine and then fails at Kafka client construction, so replace it in-cluster
  before the first rollout.
- **Secret vs ConfigMap split:** anything credential-like goes in `secret.yaml`, everything else in
  `configmap.yaml`. Values in git stay placeholders; the deployed Secret is edited in-cluster. Note that
  `.gitleaks.toml` allowlists only the CDC MariaDB sample Secret, not this one — the `YOUR_*` placeholders
  pass today, but base64-looking sample values would trip the `secret-scan` job.
- **Probes and the shutdown budget go together.** `spring.lifecycle.timeout-per-shutdown-phase` is 10s in
  `application-prod.yaml`, so `terminationGracePeriodSeconds` must stay above it; the readiness probe is what
  pulls a draining Pod out of the Service, and `management.endpoint.health.probes.enabled: true` in the prod
  profile is what makes `/actuator/health/{liveness,readiness}` exist. The Dockerfile's `-XX:MaxRAMPercentage=50.0`
  keeps the heap (1Gi) inside the 1Gi request so the Pod is not the first eviction candidate; raise the
  request, the limit and the percentage together. The startup probe allows 36 × 5s = 3 minutes.
- Port 80 is fixed in three places that must move together: `containerPort` here, `server.port` in
  `application-prod.yaml`, and `SERVER_PORT` in `application/Dockerfile`. `service.yaml` targets it by number.
- `imagePullPolicy: IfNotPresent` is safe only because the workflow tags every image with the commit sha;
  never point `$IMAGE_NAME` at a mutable tag.
- The timezone is a `hostPath` mount of `/usr/share/zoneinfo/Asia/Seoul`; a node without that file cannot
  schedule the Pod (see README prerequisites). The JVM also gets `-Duser.timezone=Asia/Seoul` from the
  Dockerfile, so both must agree.
- The agent sidecar (`@bot ask`) is intentionally absent from `deployment.yaml`; README describes how to add
  it as a native sidecar `initContainer` and which Secret keys it needs.
- The workflow's post-deploy probe hits `<ingress host>/api/slack/actuator/health` while
  `ACTUATOR_BASE_PATH` is `/actuator` and no `context-path` is set, so the production gateway strips an
  `/api/slack` prefix that the sample routes in `route/` do not model.
- **CI path filters treat these YAML files as source.** Only `**/*.md` is excluded, so a manifest-only push
  runs lint + `:application:test`, and a manifest-only PR merged to `main` builds and deploys a new image.
- Everything here is packaged into the boot jar by `processResources` even though the app never reads it.

### Testing Requirements
- There is no unit or integration test for manifests. Validate locally with
  `IMAGE_NAME=example envsubst < deployment.yaml | kubectl apply --dry-run=server -f -` and
  `kubectl apply --dry-run=client -f <file>` for the rest.
- The deploy workflow is the real check: rollout status (300s), ready-pod count equals `spec.replicas`, then
  ten attempts at the health endpoint; any failure triggers `kubectl set image` back to the previous image.
- After changing `configmap.yaml`/`secret.yaml` keys, confirm `application-prod.yaml` resolves every
  `${KEY}` without a default, or the container exits during property binding.

### Common Patterns
- One object per file, all named `code-companion-*`, all selected by the label `app: code-companion-deploy`.
- Environment injection is `envFrom` (whole Secret + whole ConfigMap), never per-key `env` entries.
- Placeholders are `YOUR_*` (Secret values), `$IMAGE_NAME` (CI substitution) and `your-*` / `your.uri`
  (routing); keep those spellings so README instructions stay accurate.

## Dependencies

### Internal
- `../application-prod.yaml` — the profile these env vars feed; `server.port: 80`
- `application/Dockerfile` — image entrypoint, `PROFILE=prod`, build-info env vars, `EXPOSE 80`
- `.github/workflows/deploy_action.yaml` — builds the image, applies `deployment.yaml`, verifies, rolls back
- `.gitleaks.toml` — secret-scan allowlist (does not cover `secret.yaml`)

### External
Kubernetes 1.31+ (OKE), Harbor registry via the `dockercred` pull secret, GNU `envsubst`, cert-manager and
either the Gateway API CRDs or the NGINX Ingress Controller for `route/`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
