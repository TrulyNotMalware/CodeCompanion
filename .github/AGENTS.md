<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-28 -->

# .github

## Purpose
CI, CD and supply-chain hygiene. Four workflows — lint and test on feature-branch pushes, security
scanning around `main`, and a full build-image-deploy-verify pipeline when a PR merges into `main` —
plus the Dependabot configuration that keeps Gradle plugins, Actions and the Docker base image current.

## Key Files
| File | Description |
|------|-------------|
| `workflows/lint.yaml` | `ktlintCheck` on pushes to `feature/*`, `feat/*`, `features/*`, `dependabot/**` |
| `workflows/simple_test_action.yaml` | Path-filtered module tests on the same branches; applies `gradle-config/apply.sh` first; uploads `build-reports.zip` on failure |
| `workflows/security_check.yaml` | On push/PR to `main`, weekly and on demand: CodeQL (`java-kotlin`, manual Gradle compile), Gradle dependency-graph submission + dependency review on PRs, gitleaks secret scan |
| `workflows/deploy_action.yaml` | On merged PR to `main`: build jar → multi-arch Docker image → push to Harbor → apply k8s manifests to Oracle OKE → rollout + health check → auto-rollback on failure |
| `dependabot.yml` | Weekly (Monday 09:00 KST) version updates for `gradle` (`/`), `github-actions` (`/`), `docker` (`/application`) and `docker-compose` (the CDC compose directory); commit prefix `chore :` to match `.gitmessage` |
| `../.gitleaks.toml` | Repo-root gitleaks config (auto-loaded by the CLI): extends the default rules and allowlists the placeholder-valued sample Secret in `cdc/k8s/yamls/mariadb/mariadb-config.yaml` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `workflows/` | The four workflow files, one row each (see `workflows/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Lint, test and deploy are path-filtered** on `application/**`, `domain/**`, `infrastructure/**`,
  `*.gradle.kts`, and (except lint) `gradle/**`, each ending with `!**/*.md` so documentation-only
  changes never start a run — critically, so a docs-only merge never reaches production. A new
  top-level source directory will be silently skipped by CI until it is added to every filter, and to
  the `source` filter in `security_check.yaml`.
- **Do not add `!` patterns to the `dorny/paths-filter` block** in `deploy_action.yaml`. Under the
  action's default `predicate-quantifier: 'some'` a negated pattern is a no-op (patterns are OR-ed),
  `'every'` would break the two-pattern `gradle` filter, and `'some-with-excludes'` — which has the
  semantics we want — only exists in paths-filter **v4**, while the workflow pins `@v3`. The
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
  failed analysis. If Kotlin extraction ever fails with the daemon, the first knob to try is
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
  fails the PR on `high`+ vulnerabilities in runtime scope.
- gitleaks runs without `GITLEAKS_LICENSE` because the repository belongs to a personal account; an
  organization-owned fork must add that secret. Push and PR runs scan only the new commits, but the
  weekly run scans the whole history, so a historical false positive fails every Monday: that is why
  `.gitleaks.toml` allowlists the sample MariaDB manifest (its `kind: Secret` carries
  `YOUR_ROOT_PASSWORD`-style placeholders). Allowlist by path only for template files, never for a
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
- reproduce the command locally (`./gradlew ktlintCheck`, `./gradlew :application:build -x test -PjarName=...`,
  `./gradlew classes --no-daemon --no-build-cache` for the CodeQL compile step);
- lint the YAML with `actionlint` (`docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color`);
- after touching `.gitleaks.toml`, replay the weekly scan locally:
  `docker run --rm -v "$PWD:/repo" -w /repo zricethezav/gitleaks:latest git /repo --log-opts="--branches --remotes" --redact`
  (`--branches --remotes` rather than `--all` so local stashes are not scanned);
- for deploy edits, confirm the k8s manifest still renders: `IMAGE_NAME=x envsubst < application/src/main/resources/k8s/deployment.yaml`;
- prefer a `feature/*` branch push to exercise lint and test paths before touching the deploy path;
  `security_check.yaml` can be exercised without a merge via `workflow_dispatch` or a PR to `main`.

### Common Patterns
- `dorny/paths-filter` for change detection, output-driven job gating (`@v3` in test/deploy, `@v4` in
  security).
- GitHub Deployments API (`actions/github-script@v8`) for `in_progress` / `success` / `failure` status.
- Environment configuration hoisted into the workflow-level `env:` block rather than repeated inline.
- Least-privilege `permissions:` declared per job; the workflow default is `contents: read`.

## Dependencies

### Internal
- `gradle-config/apply.sh` — run before CI tests
- `application/src/main/resources/k8s/` — the manifests the deploy applies
- `application/Dockerfile` context — the Docker build context is `./application`; also the file
  Dependabot's `docker` ecosystem watches

### External
GitHub Actions, Harbor registry (`harbor.registry.notypie.dev`), Oracle OKE + OCI CLI, Docker Buildx/QEMU,
GitHub code scanning (CodeQL) and dependency graph APIs, gitleaks.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
