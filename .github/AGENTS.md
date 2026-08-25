<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# .github

## Purpose
CI and CD. Three workflows: lint and test on feature-branch pushes, and a full build-image-deploy-verify
pipeline when a PR merges into `main`.

## Key Files
| File | Description |
|------|-------------|
| `workflows/lint.yaml` | `ktlintCheck` on pushes to `feature/*`, `feat/*`, `features/*` |
| `workflows/simple_test_action.yaml` | Path-filtered module tests on the same branches; applies `gradle-config/apply.sh` first; uploads `build-reports.zip` on failure |
| `workflows/deploy_action.yaml` | On merged PR to `main`: build jar → multi-arch Docker image → push to Harbor → apply k8s manifests to Oracle OKE → rollout + health check → auto-rollback on failure |

## For AI Agents

### Working In This Directory
- **All three workflows are path-filtered** on `application/**`, `domain/**`, `infrastructure/**`,
  `*.gradle.kts`, and (except lint) `gradle/**`, each ending with `!**/*.md` so documentation-only
  changes never start a run — critically, so a docs-only merge never reaches production. A new
  top-level source directory will be silently skipped by CI until it is added to every filter.
- **Do not add `!` patterns to the `dorny/paths-filter` block** in `deploy_action.yaml`. Under the
  action's default `predicate-quantifier: 'some'` a negated pattern is a no-op (patterns are OR-ed),
  `'every'` would break the two-pattern `gradle` filter, and `'some-with-excludes'` — which has the
  semantics we want — only exists in paths-filter **v4**, while the workflow pins `@v3`. The
  workflow-level `paths:` gate already makes the job unreachable for a docs-only merge, so the
  exclusion belongs there and only there.
- `simple_test_action.yaml` runs **only the changed modules'** tests via `dorny/paths-filter`, and falls
  back to the full `test` task when Gradle files change. If you add a module, extend both the `filters`
  block and the `Collect test modules` script.
- `deploy_action.yaml` triggers on `pull_request: closed` and gates every job on
  `github.event.pull_request.merged == true` — a *closed but unmerged* PR must not deploy. Preserve that
  guard.
- The deploy applies `application/src/main/resources/k8s/deployment.yaml` through `envsubst` with
  `$IMAGE_NAME`. Changing the manifest's placeholder syntax breaks the apply step.
- **Rollback is real:** the deploy job records the previous image before applying and restores it via
  `kubectl set image` on any failure. Do not remove the `Backup current deployment` step — without it
  the rollback silently no-ops.
- Health verification hits `https://api.notypie.dev/api/slack/actuator/health` with 10 retries. Changing
  the actuator base path or the ingress host requires updating `HEALTH_CHECK_ENDPOINT` /
  `K8S_APP_INGRESS_HOST` here.
- The image is built for `linux/amd64,linux/arm64` (the target cluster runs ARM instances) via QEMU +
  Buildx, with `provenance: false` and `sbom: false`. Dropping the ARM platform breaks the deployment.
- Java 25 (temurin) with Gradle caching, plus `gradle/actions/wrapper-validation` in the deploy path.
  Secrets used: `REGISTRY_USER`/`REGISTRY_PASSWORD` and the `PROD_OCI_*` set. Never inline a secret value.

### Testing Requirements
Workflows are only exercised by pushing. Before changing one:
- reproduce the command locally (`./gradlew ktlintCheck`, `./gradlew :application:build -x test -PjarName=...`);
- for deploy edits, confirm the k8s manifest still renders: `IMAGE_NAME=x envsubst < application/src/main/resources/k8s/deployment.yaml`;
- prefer a `feature/*` branch push to exercise lint and test paths before touching the deploy path.

### Common Patterns
- `dorny/paths-filter@v3` for change detection, output-driven job gating.
- GitHub Deployments API (`actions/github-script@v8`) for `in_progress` / `success` / `failure` status.
- Environment configuration hoisted into the workflow-level `env:` block rather than repeated inline.

## Dependencies

### Internal
- `gradle-config/apply.sh` — run before CI tests
- `application/src/main/resources/k8s/` — the manifests the deploy applies
- `application/Dockerfile` context — the Docker build context is `./application`

### External
GitHub Actions, Harbor registry (`harbor.registry.notypie.dev`), Oracle OKE + OCI CLI, Docker Buildx/QEMU.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
