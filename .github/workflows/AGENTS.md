<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# .github/workflows

## Purpose
The four GitHub Actions workflows. Triggers, path filters, permissions, and the constraints behind each step are
documented in `../AGENTS.md`; this file is the per-file index.

## Key Files
| File | Description |
|------|-------------|
| `lint.yaml` | `ktlintCheck` on pushes to `feature/*`, `feat/*`, `features/*`, `dependabot/**`; source-path filtered, `!**/*.md` |
| `simple_test_action.yaml` | Same triggers; runs `gradle-config/apply.sh`, then only the changed modules' tests via `dorny/paths-filter@v4` (full `test` when Gradle files change); uploads `build-reports.zip` on failure |
| `security_check.yaml` | Push/PR to `main`, weekly, manual: `changes` gate (`dorny/paths-filter@v4`, `some-with-excludes`), CodeQL `java-kotlin` with a manual `./gradlew classes --no-daemon --no-build-cache` compile, Gradle dependency-graph submission + dependency review on PRs, gitleaks secret scan |
| `deploy_action.yaml` | Merged PR to `main` only: build jar → multi-arch image → Harbor → `envsubst` apply to OKE → rollout + health check → rollback on failure |

## For AI Agents

### Working In This Directory
- Read `../AGENTS.md` first — it holds the non-obvious rules (why `!` patterns are useless inside the v3 paths-filter
  block, why CodeQL cannot use `build-mode: none`, why the `changes` job needs `pull-requests: read`, why fork PRs skip
  dependency submission, why `dependabot/**` must stay in the branch lists).
- Keep permissions least-privilege and declared per job; the workflow-level default is `contents: read`.
- Pin action majors (`@v6`, `@v4`, …); Dependabot's `github-actions` ecosystem bumps them in one grouped PR.

### Testing Requirements
- `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color` before pushing; the only accepted
  pre-existing findings are the `SC2086` shellcheck notes on `$GITHUB_OUTPUT` lines.
- Exercise `security_check.yaml` with `workflow_dispatch` or a PR to `main`; lint/test with a `feature/*` push.

### Common Patterns
- `dorny/paths-filter` outputs gate jobs; `if:` expressions compare to the string `'true'`.
- Long-running builds use `--no-daemon` and explicit `GRADLE_OPTS` rather than `gradle.properties`, which is
  git-ignored and absent on a fresh runner unless `apply.sh` runs.

## Dependencies

### Internal
- `gradle-config/apply.sh`, `gradle/wrapper/`, `application/Dockerfile`, `application/src/main/resources/k8s/deployment.yaml`
- `../dependabot.yml`, `../../.gitleaks.toml`

### External
GitHub Actions, CodeQL, gitleaks, Harbor, Oracle OKE / OCI CLI, Docker Buildx + QEMU.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
