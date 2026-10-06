<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-06 -->

# .github

## Purpose
CI, CD and supply-chain hygiene. Four workflows — lint and test on feature-branch pushes, security
scanning around `main`, and a full build-image-deploy-verify pipeline when a PR merges into `main` —
plus the Dependabot configuration that keeps Gradle plugins, Actions and the Docker base image current.

## Key Files
| File | Description |
|------|-------------|
| `workflows/lint.yaml` | `ktlintCheck` on pushes to `feature/*`, `feat/*`, `features/*`, `dependabot/**` and on every PR into `main` |
| `workflows/simple_test_action.yaml` | Dependency-aware module tests on the same triggers; applies `gradle-config/apply.sh ci` first; uploads `build-reports.zip` on failure |
| `workflows/security_check.yaml` | On push/PR to `main`, weekly and on demand: CodeQL (`java-kotlin`, manual Gradle compile), Gradle dependency-graph submission (every push to `main`, source-filtered on PRs) + dependency review on PRs, gitleaks secret scan (`GITLEAKS_VERSION` pinned) |
| `workflows/claude-code-review.yml` | Claude Code review on every non-Dependabot PR (`anthropics/claude-code-action@v1`, `code-review` plugin, inline comments); skipped for Dependabot because those runs only receive Dependabot secrets |
| `workflows/claude.yml` | `@claude` mentions in issues, PR comments and reviews start an interactive Claude Code run |
| `workflows/deploy_action.yaml` | On merged PR to `main`: build jar → multi-arch Docker image → push to Harbor → apply `deployment.yaml` to Oracle OKE → rollout + in-cluster health check → `rollout undo` on failure |
| `dependabot.yml` | Weekly (Monday 09:00 KST) version updates for `gradle` (`/`), `github-actions` (`/`), `docker` (`/application`) and `docker-compose` (the CDC compose directory); commit prefix `chore :` to match `.gitmessage` |
| `../.gitleaks.toml` | Repo-root gitleaks config (auto-loaded by the CLI): extends the default rules and allowlists the placeholder-valued sample Secret in `cdc/k8s/yamls/mariadb/mariadb-config.yaml`; its `[[allowlists]]` table needs gitleaks 8.25.0+, which is why the workflow pins `GITLEAKS_VERSION` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `workflows/` | The four workflow files, one row each (see `workflows/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Push triggers of lint and test, and the deploy trigger, are path-filtered** on `application/**`,
  `domain/**`, `infrastructure/**`, `*.gradle.kts` and `gradle/**` (the test workflow also on `gradle-config/**`,
  and its `gradle` paths-filter selects the full `test` task for a preset change, so a new CI preset runs before
  it reaches `main`), each ending with `!**/*.md`, so a
  documentation-only push starts no lint/test run and — critically — a docs-only merge never reaches
  production. The `pull_request` triggers of lint and test have **no** `paths` filter on purpose (a
  required check that never triggers stays pending and blocks the merge): a docs-only PR runs a full
  `ktlintCheck` and a test job that selects zero modules. A new top-level source directory will be silently
  skipped by CI until it is added to every filter, and to the `source` filter in `security_check.yaml`.
- **Concurrency:** lint and test cancel the in-progress run for the same workflow and ref. The deploy
  workflow's group is `deploy-production` for a merged PR and `deploy-skip-<run_id>` for a closed-but-unmerged
  one (a workflow-level `concurrency` expression may read the `github` context), with `cancel-in-progress: false`,
  so deploys run one at a time; GitHub keeps only the newest *pending* run in a group, so a merge queued behind a
  running deploy can be superseded by a later merge (whose image contains it), but no longer by an unmerged close
  whose jobs all skip.
- **CI Gradle memory:** the test and deploy workflows run `./gradle-config/apply.sh ci`, which installs
  `gradle-config/gradle-ci.properties` (3g Gradle daemon, 3g Kotlin daemon, `workers.max=4`) sized for a
  16 GB runner; the Linux preset (8g + 6g) is for developer servers. Lint runs without `apply.sh`. The daemon
  that `apply.sh` starts for its `./gradlew help` check is reused by the deploy build (no `org.gradle.daemon=false`).
- **Do not add `!` patterns to the `dorny/paths-filter` block** in `deploy_action.yaml`. Under the
  action's default `predicate-quantifier: 'some'` a negated pattern is a no-op (patterns are OR-ed),
  `'every'` would break the two-pattern `gradle` filter, and `'some-with-excludes'` — which has the
  semantics we want — is a paths-filter **v4** feature that this block does not need. The
  workflow-level `paths:` gate already makes the job unreachable for a docs-only merge, so the
  exclusion belongs there and only there.
- `security_check.yaml` is the one place that uses `dorny/paths-filter@v4` with
  `predicate-quantifier: some-with-excludes`, because it has no workflow-level `paths:` gate: the
  secret scan must run on *every* push and PR (a token can land in a `.md` or a YAML), so the
  markdown exclusion has to live inside the `changes` job that gates only CodeQL and the dependency
  graph. Scheduled and manual runs skip the filter and analyse everything.
- **CodeQL must compile the Kotlin.** `build-mode: none` analyses Java only and skips Kotlin with a
  warning, and this project is pure Kotlin, so the job runs `./gradlew classes --no-daemon
  --no-build-cache` between `init` and `analyze`. Keep both flags: CodeQL only extracts code compiled
  by a JVM its tracer started, so a reused daemon or a build-cache hit yields an empty database and a
  failed analysis. The step also passes explicit heaps (`-Dorg.gradle.jvmargs`,
  `-Pkotlin.daemon.jvmargs`): `gradle.properties` is git-ignored and this job does not run `apply.sh`, so
  the Kotlin daemon otherwise gets the default heap and the extractor inside it dies with `GC overhead
  limit exceeded` (seen on `:infrastructure:compileKotlin`). If Kotlin extraction still fails with the daemon, the next knob is
  `-Pkotlin.compiler.execution.strategy=in-process`. Supported Kotlin range is published at
  https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/ — check it before
  bumping the Kotlin plugin past what CodeQL lists. An unsupported version fails `compileKotlin` under
  the tracer with `Kotlin version X is too recent. CodeQL currently supports versions below X`
  (seen with 2.4.20, so the plugin is pinned to 2.4.10); a Dependabot `kotlin` group PR that goes red
  here for that reason waits for a CodeQL release rather than getting merged.
- **The dependency graph is populated only by `gradle/actions/dependency-submission`.** GitHub cannot
  parse Gradle build scripts by itself, so without that step Dependabot alerts and
  `dependency-review-action` see nothing. It needs `contents: write`, granted at job level: Dependabot
  runs get a read-only token by default and the explicit `permissions:` key is what elevates it, but a
  PR from a fork can never obtain it, so the job's `if` skips fork PRs outright rather than failing
  their submission with a 403 (CodeQL has no such gate — code scanning uploads are allowed for fork
  PRs). The review step waits for the just-submitted snapshot via `retry-on-snapshot-warnings` and
  fails the PR on `high`+ vulnerabilities in runtime scope. The job runs on **every** push to `main`,
  bypassing the `changes` gate: the review compares the PR head against the base commit's snapshot, and
  after a docs-only merge left `main` without one (c2947fae, 2026-09-28) every later PR saw the whole
  graph as newly added and failed on advisories that `main` already carried.
- gitleaks runs without `GITLEAKS_LICENSE` because the repository belongs to a personal account; an
  organization-owned fork must add that secret. Push and PR runs scan only the new commits, but the
  weekly run scans the whole history, so a historical false positive fails every Monday: that is why
  `.gitleaks.toml` allowlists the sample MariaDB manifest (its `kind: Secret` carries
  `YOUR_ROOT_PASSWORD`-style placeholders). The allowlist is a `[[allowlists]]` table, which gitleaks
  reads only from 8.25.0; the action defaulted to 8.24.3 and ignored it (weekly failures 2026-09-28 and
  2026-10-05), so the step pins `GITLEAKS_VERSION` — keep it at or above 8.25.0 when bumping. Allowlist by path only for template files, never for a
  real leak — rotate and rewrite instead. Test fixtures use `xoxb-test…`-style placeholders that the
  Slack token rules do not match; keep any new fixture tokens equally obviously fake.
- **Dependabot resolves the shared versions only through `$name` templates.** Its Gradle parser
  understands `extra["name"] = "…"` / `extra.set("name", "…")` declarations and `$name` / `${name}`
  references, but not an `ext { set(…) }` block, `by extra("…")` or `${rootProject.extra.get("…")}`
  (source: dependabot-core `gradle/lib/dependabot/gradle/file_parser.rb` `PROPERTY_REGEX` and
  `file_parser/property_value_finder.rb` declaration regexes).
  The root build therefore declares `extra["name"] = "…"`, scripts read it as `val name =
  extra["name"] as String` / `rootProject.extra["name"] as String` (the `by extra` delegate is deprecated,
  removal scheduled for Gradle 10) and interpolate `$name`; keep new dependencies in that form or they
  silently drop out of version updates. Version-less
  coordinates managed by the Spring Boot / Jackson / Spring AI / kotest BOMs move with the BOM
  version; the Boot BOM and Boot plugin share the `spring` group so they bump in one PR.
- Dependabot pushes to `dependabot/**`, which is why that pattern is in the lint and test branch
  lists — remove it and Dependabot PRs arrive unverified. Actions-only bumps touch nothing under the
  path filters and therefore trigger only `security_check.yaml`, which is the intended behaviour.
- `simple_test_action.yaml` selects test modules via `dorny/paths-filter` **following the dependency
  direction** (application → infrastructure → domain): a `domain` or Gradle change runs the full `test`
  task, `infrastructure` runs its own suite plus `:application:test`, `application` runs only itself. If
  you add a module, extend both the `filters` block and the `Collect test modules` script. On
  `pull_request` events the action lists files through the API, which is why the workflow grants
  `pull-requests: read`; the same scope is granted in `deploy_action.yaml` for its `check-changes` job.
- `deploy_action.yaml` triggers on `pull_request: closed` and gates every job on
  `github.event.pull_request.merged == true` — a *closed but unmerged* PR must not deploy. Preserve that
  guard.
- **`deploy_action.yaml` runs every `run:` step with pipefail.** Its workflow-level `defaults.run.shell: bash`
  makes GitHub invoke `bash --noprofile --norc -eo pipefail {0}`; without an explicit `shell` the default is
  `bash -e {0}`, where a pipeline takes the status of its last command, so a failing `jq` in
  `jq -cS '.spec.template' | sha256sum | cut` hashed empty input and the rollback's change check could read
  "unchanged". The Ready-pod count keeps its `|| true`, because `grep -c` exits 1 on zero matches. The other
  workflows set no default; add the same `defaults` block before adding a pipe whose upstream failure must fail
  a step there.
- The deploy applies `application/src/main/resources/k8s/deployment.yaml` through `envsubst '${IMAGE_NAME}'`.
  The variable list is explicit because the job env holds the `PROD_OCI_*` secrets: an unrestricted
  `envsubst` would write any of them into the cluster the moment the manifest gained another `$NAME`.
- The `PROD_OCI_*` variables are job-level, not scoped to the `configure-kubectl-oke` step, because the
  kubeconfig that action writes (v1.5.0, `tokenVersion: 2.0.0`) runs `oci ce cluster generate-token` on every
  `kubectl` call — step-scoping them breaks all later kubectl steps.
- **Timeouts follow the `Recreate` rollout.** `DEPLOYMENT_ROLLOUT_TIMEOUT` (450s) covers the old Pods' grace
  (`terminationGracePeriodSeconds`, 180s), the startup probe (36 × 5s) and the first readiness check (10s), plus
  scheduling and image pull; the deploy job's `timeout-minutes: 25` covers a rollout, the health check (~2.5 min)
  and a rollback rollout. `configurations/ShutdownBudgetTest` reads this workflow and the manifest and fails when the
  rollout timeout or the job timeout falls below that sum; raise the grace or the startup probe and these follow.
- **Rollback:** `Backup current deployment` reads the Deployment once as JSON and records its
  `deployment.kubernetes.io/revision`, a sha256 of `.spec.template` (`jq -cS`, so key order does not matter) and
  the image (for the log). `NotFound` records `none` (first deploy); any other lookup error fails the step before
  anything is applied, instead of silently disabling the rollback. On failure the rollback step re-reads the
  Deployment (3 tries, 5s apart) and runs `kubectl rollout undo --to-revision=<recorded>` + `rollout status`
  when the template hash **or** the revision differs from the backup. The template is compared because the API
  server stores it synchronously on apply, while the revision annotation is written later by the controller (a
  failure right after the apply can still show the old revision). If the Deployment cannot be read at all, it
  logs an `::error::` and undoes anyway; `rollout undo` skips when the template already matches. A same-SHA
  redeploy or a failure before the apply leaves the template unchanged and logs "nothing to roll back". Its
  outcome (`rolled back` / `not rolled back` / `rollback failed`) feeds the failure deployment status. Do not
  remove the backup step — without the recorded revision the rollback deliberately does nothing.
- **Health verification is in-cluster and gates on readiness:** after rollout and the Ready-pod count (from
  the `Ready` condition, not `phase == Running`; `-lt` so a terminating old Pod does not fail the count), the
  `health` step polls `/actuator/health/readiness` for up to ~2 minutes and requires `jq -e '.status == "UP"'`
  (it fails with an explicit error if `jq` is missing from the runner). Each attempt tries, and logs by name:
  1. **service proxy** — `kubectl get --raw /api/v1/namespaces/api-service/services/code-companion-svc:80/proxy/actuator/health/readiness`.
     Needs RBAC `get` on `services/proxy` in `api-service`; if the Role restricts `resourceNames`, the name the
     authorizer checks is `code-companion-svc:80` (the `<service>:<port>` segment of the URL), not
     `code-companion-svc`. It also needs the API server to reach pod IPs, which is not guaranteed on every
     cluster network.
  2. **pod exec** — `kubectl exec <pod> -c code-companion-deploy -- wget -qO- -T 10
     http://localhost:80/actuator/health/readiness` (busybox `wget` in the alpine JRE image; the container
     listens on 80) on **every** Pod of the current revision: the step reads the Deployment's
     `deployment.kubernetes.io/revision`, finds the ReplicaSet with the same revision annotation, lists Pods by
     its `pod-template-hash` label and skips any with a `deletionTimestamp`. A Pod whose exec fails (a `503`
     makes `wget` exit non-zero) fails the attempt, and a Pod that answers without `UP` is reported as the
     body, so the loop retries until all of them are UP. It replaced `kubectl exec deploy/<name>`, which picks
     one Pod by readiness and age and does not skip terminating ones, so right after `rollout status` it could
     pick an old Pod still answering `UP` during its 5s `preStop` sleep and pass the gate on the old binary.
     Needs RBAC `create` on `pods/exec`, `get` on `deployments`, `list` on `replicasets` and `pods` in
     `api-service` (the last three are already needed by the backup, `rollout undo` and the Ready-pod count).
     The service proxy path checks only the Pod the Service routes to; terminating Pods are already out of its
     endpoints.
  The aggregate `/actuator/health` is fetched once after readiness passes, for the log only: it includes
  `OutboxHealthIndicator`, which can be DOWN for reasons unrelated to the release (connector lag, a looping
  message, rows orphaned by the rollout itself), and gating on it would roll back the very release meant to fix
  that. The management base path is fixed at `/actuator` in `application-prod.yaml`; the probes in
  `k8s/deployment.yaml` and this step hard-code it, as they do the Service name and port 80.
  The health check never uses the public host. Measured 2026-09-28 against `https://api.notypie.dev`: every
  probed path, including nonexistent ones, answered `401` with `WWW-Authenticate: Bearer` from `istio-envoy`,
  except `GET /actuator/health`, which answered a `404` JSON body that is not this application's error format.
  The host is fronted by a bearer-authenticating layer that is not part of this repository, so what it forwards
  to the app is unknown from outside and must be confirmed by whoever operates it; the 401s say nothing about
  this app's Slack signature filter.
- **Rollback is scoped to the deploy steps:** `Rollback on failure` runs only when `apply`, `rollout`, `verify` or
  `health` failed (`steps.<id>.outcome`), so a GitHub API error in `Update deployment status to success` marks
  the deployment failed but does not undo a healthy rollout. Keep the step `id`s if you rename the steps.
- The deploy build runs the full `./gradlew build` (all module tests + `ktlintCheck`) on purpose: a PR can
  be merged while its checks are still pending, so this build is the last gate before an image is pushed.
  Do not reintroduce `-x test`.
- The image is built for `linux/amd64,linux/arm64` (the target cluster runs ARM instances) via QEMU +
  Buildx, with `provenance: false` and `sbom: false`. Dropping the ARM platform breaks the deployment.
- Java 25 (temurin) with Gradle caching, plus `gradle/actions/wrapper-validation` in the deploy path.
  Secrets used: `REGISTRY_USER`/`REGISTRY_PASSWORD` and the `PROD_OCI_*` set. Never inline a secret value.

### Testing Requirements
Workflows are only exercised by pushing. Before changing one:
- reproduce the command locally (`./gradlew ktlintCheck`, `./gradlew build -PjarName=...`,
  `./gradlew classes --no-daemon --no-build-cache` for the CodeQL compile step);
- lint the YAML with `actionlint` (`docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color`);
- after touching `.gitleaks.toml`, replay the weekly scan locally:
  `docker run --rm -v "$PWD:/repo" -w /repo ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --log-opts="--branches --remotes" --redact` (same version as `GITLEAKS_VERSION` in the workflow; `latest` may read the config differently)
  (`--branches --remotes` rather than `--all` so local stashes are not scanned);
- for deploy edits, confirm the k8s manifest still renders: `IMAGE_NAME=x envsubst '${IMAGE_NAME}' < application/src/main/resources/k8s/deployment.yaml`;
- prefer a `feature/*` branch push to exercise lint and test paths before touching the deploy path;
  `security_check.yaml` can be exercised without a merge via `workflow_dispatch` or a PR to `main`.

### Common Patterns
- `dorny/paths-filter@v4` for change detection, output-driven job gating.
- GitHub Deployments API (`actions/github-script@v9`) for `in_progress` / `success` / `failure` status.
- Environment configuration hoisted into the workflow-level `env:` block rather than repeated inline.
- Least-privilege `permissions:`: every workflow declares a `contents: read` baseline. `pull-requests: read` for
  paths-filter is job-level in `deploy_action.yaml` (`check-changes`) and `security_check.yaml` (`changes`), and
  workflow-level in the single-job `simple_test_action.yaml`. The deploy workflow's `deployments: write` covers
  the Deployments API calls; there is no Commit Status API call, so it has no `statuses: write`.

## Dependencies

### Internal
- `gradle-config/apply.sh ci` / `gradle-ci.properties` — run before CI tests and the deploy build
- `application/src/main/resources/k8s/` — the manifests the deploy applies
- `application/Dockerfile` context — the Docker build context is `./application`; also the file
  Dependabot's `docker` ecosystem watches

### External
GitHub Actions, Harbor registry (`harbor.registry.notypie.dev`), Oracle OKE + OCI CLI, Docker Buildx/QEMU,
GitHub code scanning (CodeQL) and dependency graph APIs, gitleaks.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
